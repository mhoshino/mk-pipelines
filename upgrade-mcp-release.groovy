/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   TARGET_MCP_VERSION         Version of MCP to upgrade to
 *   GIT_REFSPEC                Git repo ref to be used
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_URL            Salt API server location
 *     SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *     BATCH_SIZE                 Use batch sizing during upgrade for large envs
 *     UPGRADE_SALTSTACK          Upgrade SaltStack packages to new version.
 *     UPDATE_CLUSTER_MODEL       Update MCP version parameter in cluster model
 *     UPDATE_PIPELINES           Update pipeline repositories on Gerrit
 *     UPDATE_LOCAL_REPOS         Update local repositories
 */

salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
def pipelineTimeout = 12
venvPepper = "venvPepper"
workspace = ""
def saltMastURL = ''
def saltMastCreds = ''

def triggerMirrorJob(String jobName, String reclassSystemBranch) {
    params = jenkinsUtils.getJobParameters(jobName)
    try {
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: params.get('BRANCHES')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    } catch (Exception updateErr) {
        common.warningMsg(updateErr)
        common.warningMsg('Attempt to update git repo in failsafe manner')
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: reclassSystemBranch.replace('origin/', '')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    }
}

def updateSaltStack(target, pkgs) {
    salt.cmdRun(venvPepper, "I@salt:master", "salt -C '${target}' --async pkg.install force_yes=True pkgs='$pkgs'")
    // can't use same function from pipeline lib, as at the moment of running upgrade pipeline Jenkins
    // still using pipeline lib from current old mcp-version
    common.retry(20, 60) {
        salt.minionsReachable(venvPepper, 'I@salt:master', '*')
        def running = salt.runSaltProcessStep(venvPepper, target, 'saltutil.running', [], null, true, 5)
        for (value in running.get("return")[0].values()) {
            if (value != []) {
                throw new Exception("Not all salt-minions are ready for execution")
            }
        }
    }

    def saltVersion = salt.getPillar(venvPepper, 'I@salt:master', '_param:salt_version').get('return')[0].values()[0]
    def saltMinionVersions = salt.cmdRun(venvPepper, target, "apt-cache policy salt-common |  awk '/Installed/ && /$saltVersion/'").get("return")
    def saltMinionVersion = ""

    for (minion in saltMinionVersions[0].keySet()) {
        saltMinionVersion = saltMinionVersions[0].get(minion).replace("Salt command execution success", "").trim()
        if (saltMinionVersion == "") {
            error("Installed version of Salt on $minion doesn't match specified version in the model.")
        }
    }
}

def getWorkerThreads(saltId) {
    if (env.getEnvironment().containsKey('SALT_MASTER_OPT_WORKER_THREADS')) {
        return env['SALT_MASTER_OPT_WORKER_THREADS'].toString()
    }
    def threads = salt.cmdRun(saltId, "I@salt:master", "cat /etc/salt/master.d/master.conf | grep worker_threads | cut -f 2 -d ':'", true, null, true)
    return threads['return'][0].values()[0].replaceAll('Salt command execution success','').trim()
}

def wa29352(String cname) {
    // WA for PROD-29352. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/12/openssh/client/root.yml
    // Default soft-param has been removed, what now makes not possible to render some old env's.
    // Like fix, we found copy-paste already generated key from backups, to secrets.yml with correct key name
    def wa29352ClassName = 'cluster.' + cname + '.infra.secrets_root_wa29352'
    def wa29352File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_root_wa29352.yml"
    def wa29352SecretsFile = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets.yml"
    def _tempFile = '/tmp/wa29352_' + UUID.randomUUID().toString().take(8)
    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "grep -qiv root_private_key ${wa29352SecretsFile}", true, null, false)
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29352File}", true, null, false)
    }
    catch (Exception ex) {
        common.infoMsg('Work-around for PROD-29352 already applied, nothing todo')
        return
    }
    def rKeysDict = [
        'parameters': [
            '_param': [
                'root_private_key': salt.getPillar(venvPepper, 'I@salt:master', '_param:root_private_key').get('return')[0].values()[0].trim(),
                'root_public_key' : '',
            ]
        ]
    ]
    // save root key,and generate public one from it
    writeFile file: _tempFile, text: rKeysDict['parameters']['_param']['root_private_key'].toString().trim()
    sh('chmod 0600 ' + _tempFile)
    rKeysDict['parameters']['_param']['root_public_key'] = sh(script: "ssh-keygen -q -y -f ${_tempFile}", returnStdout: true).trim()
    sh('rm -fv ' + _tempFile)
    writeYaml file: _tempFile, data: rKeysDict
    def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
    salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29352File}", false, null, false)
    common.infoMsg("Add $wa29352ClassName class into secrets.yml")

    // Add 'classes:' directive
    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q '${wa29352ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29352ClassName' infra/secrets.yml")
    salt.fullRefresh(venvPepper, '*')
    sh('rm -fv ' + _tempFile)
    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && git status && " +
            "git add ${wa29352File} && git add -u && git commit --allow-empty -m 'Cluster model updated with WA for PROD-29352. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/ at ${common.getDatetime()}' ")
    common.infoMsg('Work-around for PROD-29352 successfully applied')
}

def wa29155(ArrayList saltMinions, String cname) {
    // WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/
    // CHeck for existence cmp nodes, and try to render it. Is failed, apply ssh-key wa
    def ret = ''
    def patched = false
    def wa29155ClassName = 'cluster.' + cname + '.infra.secrets_nova_wa29155'
    def wa29155File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_nova_wa29155.yml"

    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29155File}", true, null, false)
    }
    catch (Exception ex) {
        common.infoMsg('Work-around for PROD-29155 already apply, nothing todo')
        return
    }
    salt.fullRefresh(venvPepper, 'I@salt:master')
    for (String minion in saltMinions) {
        // First attempt, second will be performed in next validateReclassModel() stages
        try {
            salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        } catch (Exception e) {
            common.errorMsg(e.toString())
            if (patched) {
                error("Node: ${minion} failed to render after reclass-system upgrade!WA29155 probably didn't help.")
            }
            // check, that failed exactly by our case,  by key-length check.
            def missed_key = salt.getPillar(venvPepper, minion, '_param:nova_compute_ssh_private').get("return")[0].values()[0]
            if (missed_key != '') {
                error("Node: ${minion} failed to render after reclass-system upgrade!")
            }
            common.warningMsg('Perform: Attempt to apply WA for PROD-29155\n' +
                'See https://gerrit.mcp.mirantis.com/#/c/37932/ for more info')
            common.warningMsg('WA-PROD-29155 Generating new ssh key at master node')
            def _tempFile = "/tmp/nova_wa29155_" + UUID.randomUUID().toString().take(8)
            common.infoMsg('Perform: generation NEW ssh-private key for nova-compute')
            salt.cmdRun(venvPepper, 'I@salt:master', "ssh-keygen -f ${_tempFile} -N '' -q")
            def _pub_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}.pub").get('return')[0].values()[0].trim()
            def _priv_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}").get('return')[0].values()[0].trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "rm -fv ${_tempFile}", false, null, false)
            def novaKeysDict = [
                "parameters": [
                    "_param": [
                        "nova_compute_ssh_private": _priv_k,
                        "nova_compute_ssh_public" : _pub_k
                    ]
                ]
            ]
            writeYaml file: _tempFile, data: novaKeysDict
            def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29155File}", false, null, false)
            common.infoMsg("Add $wa29155ClassName class into secrets.yml")

            // Add 'classes:' directive
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q '${wa29155ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29155ClassName' infra/secrets.yml")
            salt.fullRefresh(venvPepper, 'I@salt:master')
            salt.fullRefresh(venvPepper, saltMinions)
            patched = true
        }
    }
    if (patched) {
        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && git status && " +
            "git add ${wa29155File} && git add -u && git commit --allow-empty -m 'Cluster model updated with WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/ at ${common.getDatetime()}' ")
        common.infoMsg('Work-around for PROD-29155 successfully applied')
    }

}

def wa32284(String clusterName) {
    def clientGluster = salt.getPillar(venvPepper, 'I@salt:master', "glusterfs:client:enabled").get("return")[0].values()[0]
    def pkiGluster = salt.getPillar(venvPepper, 'I@salt:master', "glusterfs:client:volumes:salt_pki").get("return")[0].values()[0]
    def nginxEnabledAtMaster = salt.getPillar(venvPepper, 'I@salt:master', 'nginx:server:enabled').get('return')[0].values()[0]
    if (nginxEnabledAtMaster.toString().toLowerCase() == 'true' && clientGluster.toString().toLowerCase() == 'true' && pkiGluster) {
        def nginxRequires = salt.getPillar(venvPepper, 'I@salt:master', 'nginx:server:wait_for_service').get('return')[0].values()[0]
        if (nginxRequires.isEmpty()) {
            def nginxRequiresClassName = "cluster.${clusterName}.infra.config.nginx_requires_wa32284"
            def nginxRequiresClassFile = "/srv/salt/reclass/classes/cluster/${clusterName}/infra/config/nginx_requires_wa32284.yml"
            def nginxRequiresBlock = ['parameters': ['nginx': ['server': ['wait_for_service': ['srv-salt-pki.mount'] ] ] ] ]
            def _tempFile = '/tmp/wa32284_' + UUID.randomUUID().toString().take(8)
            writeYaml file: _tempFile , data: nginxRequiresBlock
            def nginxRequiresBlockString = sh(script: "cat ${_tempFile}", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${clusterName} && " +
                "sed -i '/^parameters:/i - ${nginxRequiresClassName}' infra/config/init.yml")
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${nginxRequiresBlockString}' > ${nginxRequiresClassFile}", false, null, false)
        }
    }
}

def wa32182(String cluster_name) {
    if (salt.testTarget(venvPepper, 'I@opencontrail:control or I@opencontrail:collector')) {
        def clusterModelPath = "/srv/salt/reclass/classes/cluster/${cluster_name}"
        def fixFile = "${clusterModelPath}/opencontrail/common_wa32182.yml"
        def usualFile = "${clusterModelPath}/opencontrail/common.yml"
        def fixFileContent = "classes:\n- system.opencontrail.common\n"
        salt.cmdRun(venvPepper, 'I@salt:master', "test -f ${fixFile} -o -f ${usualFile} || echo '${fixFileContent}' > ${fixFile}")
        def contrailFiles = ['opencontrail/analytics.yml', 'opencontrail/control.yml', 'openstack/compute/init.yml']
        if (salt.testTarget(venvPepper, "I@kubernetes:master")) {
            contrailFiles.add('kubernetes/compute.yml')
        }
        for(String contrailFile in contrailFiles) {
            contrailFile = "${clusterModelPath}/${contrailFile}"
            def containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- cluster\\.${cluster_name}\\.opencontrail\\.common(_wa32182)?\$' ${contrailFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            if (containsFix) {
                continue
            } else {
                salt.cmdRun(venvPepper, 'I@salt:master', "grep -q -E '^parameters:' ${contrailFile} && sed -i '/^parameters:/i - cluster.${cluster_name}.opencontrail.common_wa32182' ${contrailFile} || " +
                    "echo '- cluster.${cluster_name}.opencontrail.common_wa32182' >> ${contrailFile}")
            }
        }
    }
}

def wa33867(String cluster_name) {
    if (salt.testTarget(venvPepper, 'I@opencontrail:control or I@opencontrail:collector')) {
        def contrailControlFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/opencontrail/control.yml"
        def line = salt.cmdRun(venvPepper, 'I@salt:master', "awk '/^- cluster.${cluster_name}.infra.backup.client_zookeeper/ {getline; print \$0}' ${contrailControlFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        if (line == "- cluster.${cluster_name}.infra") {
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^- cluster.${cluster_name}.infra\$/d' ${contrailControlFile}")
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^- cluster.${cluster_name}.infra.backup.client_zookeeper\$/i - cluster.${cluster_name}.infra' ${contrailControlFile}")
        }
    }
}

def wa33771(String cluster_name) {
    def octaviaEnabled = salt.getMinions(venvPepper, 'I@octavia:api:enabled')
    def octaviaWSGI = salt.getMinions(venvPepper, 'I@apache:server:site:octavia_api')
    if (octaviaEnabled && ! octaviaWSGI) {
        def openstackControl = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/control.yml"
        def octaviaFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/octavia_wa33771.yml"
        def octaviaContext = [
            'classes': [ 'system.apache.server.site.octavia' ],
            'parameters': [
                '_param': [ 'apache_octavia_api_address' : '${_param:cluster_local_address}' ],
                'apache': [ 'server': [ 'site': [ 'apache_proxy_openstack_api_octavia': [ 'enabled': false ] ] ] ]
            ]
        ]
        def _tempFile = '/tmp/wa33771' + UUID.randomUUID().toString().take(8)
        writeYaml file: _tempFile , data: octaviaContext
        def octaviaFileContent = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
        salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.openstack.octavia_wa33771' ${openstackControl}")
        salt.cmdRun(venvPepper, 'I@salt:master', "echo '${octaviaFileContent}' | base64 -d > ${octaviaFile}", false, null, false)
    }
}

def wa33930_33931(String cluster_name) {
    def openstackControlFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/control.yml"
    def fixName = 'clients_common_wa33930_33931'
    def fixFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/${fixName}.yml"
    def containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- cluster\\.${cluster_name}\\.openstack\\.${fixName}\$' ${openstackControlFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    if (! containsFix) {
        def fixContext = [
            'classes': [ 'service.nova.client', 'service.glance.client', 'service.neutron.client' ]
        ]
        if (salt.getMinions(venvPepper, 'I@manila:api:enabled')) {
            fixContext['classes'] << 'service.manila.client'
        }
        if (salt.getMinions(venvPepper, 'I@ironic:api:enabled')) {
            fixContext['classes'] << 'service.ironic.client'
        }
        if (salt.getMinions(venvPepper, 'I@gnocchi:server:enabled')) {
            fixContext['classes'] << 'service.gnocchi.client'
        }
        if (salt.getMinions(venvPepper, 'I@barbican:server:enabled')) {
            fixContext['classes'] << 'service.barbican.client.single'
        }
        def _tempFile = '/tmp/wa33930_33931' + UUID.randomUUID().toString().take(8)
        writeYaml file: _tempFile , data: fixContext
        def fixFileContent = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
        salt.cmdRun(venvPepper, 'I@salt:master', "echo '${fixFileContent}' | base64 -d > ${fixFile}", false, null, false)
        salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.openstack.${fixName}' ${openstackControlFile}")
    }
}

def archiveReclassInventory(filename) {
    def _tmp_file = '/tmp/' + filename + UUID.randomUUID().toString().take(8)
    // jenkins may fail at overheap. Compress data with gzip like WA
    def ret = salt.cmdRun(venvPepper, 'I@salt:master', 'reclass -i  2>/dev/null | gzip -9 -c | base64', true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    def _tmp = sh(script: "echo '$ret'  > ${_tmp_file}", returnStdout: false)
    sh(script: "cat ${_tmp_file} | base64 -d | gzip -d > $filename", returnStdout: false)
    archiveArtifacts artifacts: filename
    sh(script: "rm -v ${_tmp_file}|| true")
}

def validateReclassModel(ArrayList saltMinions, String suffix) {
    try {
        for (String minion in saltMinions) {
            common.infoMsg("Reclass model validation for minion ${minion}...")
            def reclassInv = salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            writeFile file: "inventory-${minion}-${suffix}.out", text: reclassInv.toString()
        }
    } catch (Exception e) {
        common.errorMsg('Can not validate current Reclass model. Inspect failed minion manually.')
        error(e.toString())
    }
}

def archiveReclassModelChanges(ArrayList saltMinions, String oldSuffix = 'before', String newSuffix = 'after') {
    for (String minion in saltMinions) {
        def fileName = "reclass-model-${minion}-diff.out"
        sh "diff -u inventory-${minion}-${oldSuffix}.out inventory-${minion}-${newSuffix}.out > ${fileName} || true"
        archiveArtifacts artifacts: "${fileName}"
    }
}

if (common.validInputParam('PIPELINE_TIMEOUT')) {
    try {
        pipelineTimeout = env.PIPELINE_TIMEOUT.toInteger()
    } catch (Exception e) {
        common.warningMsg("Provided PIPELINE_TIMEOUT parameter has invalid value: ${env.PIPELINE_TIMEOUT} - should be interger")
    }
}

timeout(time: pipelineTimeout, unit: 'HOURS') {
    node("python") {
        try {
            stage('Update Reclass and Salt-Formulas') {
                common.infoMsg('Perform: Full salt sync')
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
                salt.fullRefresh(venvPepper, '*', null)
            }
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
