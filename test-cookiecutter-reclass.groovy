/*
Able to be triggered from Gerrit if :
Variators:
Modes:
1) manual run via job-build , possible to pass refspec
   TODO: currently impossible to use custom COOKIECUTTER_TEMPLATE_URL| RECLASS_SYSTEM_URL Gerrit-one always used.
   - for CC
   - Reclass

2) gerrit trigger
   Automatically switches if GERRIT_PROJECT variable detected
   Always test GERRIT_REFSPEC VS GERRIT_BRANCH-master version of opposite project
 */

common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()

slaveNode = env.SLAVE_NODE ?: 'docker'
checkIncludeOrder = env.CHECK_INCLUDE_ORDER ?: false

// Global var's
alreadyMerged = false
gerritConData = [credentialsId       : env.CREDENTIALS_ID,
                 gerritName          : env.GERRIT_NAME ?: 'mcp-jenkins',
                 gerritHost          : env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.net',
                 gerritScheme        : env.GERRIT_SCHEME ?: 'ssh',
                 gerritPort          : env.GERRIT_PORT ?: '29418',
                 gerritRefSpec       : null,
                 gerritProject       : null,
                 withWipeOut         : true,
                 GERRIT_CHANGE_NUMBER: null]
//
//ccTemplatesRepo = env.COOKIECUTTER_TEMPLATE_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/mk/cookiecutter-templates'
gerritDataCCHEAD = [:]
gerritDataCC = [:]
gerritDataCC << gerritConData
gerritDataCC['gerritBranch'] = env.COOKIECUTTER_TEMPLATE_BRANCH ?: 'master'
gerritDataCC['gerritProject'] = 'mk/cookiecutter-templates'
//
//reclassSystemRepo = env.RECLASS_SYSTEM_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/salt-models/reclass-system'
gerritDataRSHEAD = [:]
gerritDataRS = [:]
gerritDataRS << gerritConData
gerritDataRS['gerritBranch'] = env.RECLASS_MODEL_BRANCH ?: 'master'
gerritDataRS['gerritProject'] = 'salt-models/reclass-system'

// version of debRepos, aka formulas\reclass
testDistribRevision = env.DISTRIB_REVISION ?: 'nightly'
reclassVersion = 'v1.5.4'
if (env.RECLASS_VERSION) {
    reclassVersion = env.RECLASS_VERSION
}
// Name of sub-test chunk job
chunkJobName = "test-mk-cookiecutter-templates-chunk"
testModelBuildsData = [:]

def generateSaltMaster(modEnv, clusterDomain, clusterName) {
    def nodeFile = "${modEnv}/nodes/cfg01.${clusterDomain}.yml"
    def nodeString = """classes:
- cluster.${clusterName}.infra.config
parameters:
    _param:
        linux_system_codename: xenial
        reclass_data_revision: master
    linux:
        system:
            name: cfg01
            domain: ${clusterDomain}
"""
    sh "mkdir -p ${modEnv}/nodes/"
    println "Create file ${nodeFile}"
    writeFile(file: nodeFile, text: nodeString)
}

/**
 *
 * @param contextFile - path to `contexts/XXX.yaml file`
 * @param virtualenv - pyvenv with CC and dep's
 * @param templateEnvDir - root of CookieCutter
 * @return
 */

def generateModel(contextFile, virtualenv, templateEnvDir) {
    def modelEnv = "${templateEnvDir}/model"
    def basename = common.GetBaseName(contextFile, '.yml')
    def generatedModel = "${modelEnv}/${basename}"
    def content = readFile(file: "${templateEnvDir}/contexts/${contextFile}")
    def templateContext = readYaml text: content
    def clusterDomain = templateContext.default_context.cluster_domain
    def clusterName = templateContext.default_context.cluster_name
    def outputDestination = "${generatedModel}/classes/cluster/${clusterName}"
    def templateBaseDir = templateEnvDir
    def templateDir = "${templateEnvDir}/dir"
    def templateOutputDir = templateBaseDir
    dir(templateEnvDir) {
        sh(script: "rm -rf ${generatedModel} || true")
        common.infoMsg("Generating model from context ${contextFile}")
        def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "oss", "stacklight", "ceph"]
        for (product in productList) {

            // get templateOutputDir and productDir
            templateOutputDir = "${templateEnvDir}/output/${product}"
            productDir = product
            templateDir = "${templateEnvDir}/cluster_product/${productDir}"
            // Bw for 2018.8.1 and older releases
            if (product.startsWith("stacklight") && (!fileExists(templateDir))) {
                common.warningMsg("Old release detected! productDir => 'stacklight2' ")
                productDir = "stacklight2"
                templateDir = "${templateEnvDir}/cluster_product/${productDir}"
            }
            if (product == "infra" || (templateContext.default_context["${product}_enabled"]
                && templateContext.default_context["${product}_enabled"].toBoolean())) {

                common.infoMsg("Generating product " + product + " from " + templateDir + " to " + templateOutputDir)

                sh "rm -rf ${templateOutputDir} || true"
                sh "mkdir -p ${templateOutputDir}"
                sh "mkdir -p ${outputDestination}"

                python.buildCookiecutterTemplate(templateDir, content, templateOutputDir, virtualenv, templateBaseDir)
                sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
            } else {
                common.warningMsg("Product " + product + " is disabled")
            }
        }
        generateSaltMaster(generatedModel, clusterDomain, clusterName)
    }
}

def getAndUnpackNodesInfoArtifact(jobName, copyTo, build) {
    return {
        dir(copyTo) {
            copyArtifacts(projectName: jobName, selector: specific(build), filter: "nodesinfo.tar.gz")
            sh "tar -xf nodesinfo.tar.gz"
            sh "rm -v nodesinfo.tar.gz"
        }
    }
}

def testModel(modelFile, reclassArtifactName, artifactCopyPath) {
    // modelFile - `modelfiname` from model/modelfiname/modelfiname.yaml
    //* Grub all models and send it to check in paralell - by one in thread.
    def _uuid = "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}_${modelFile.toLowerCase()}_" + UUID.randomUUID().toString().take(8)
    def _values_string = """
  ---
  MODELS_TARGZ: "${env.BUILD_URL}/artifact/${reclassArtifactName}"
  DockerCName: "${_uuid}"
  testReclassEnv: "model/${modelFile}/"
  modelFile: "contexts/${modelFile}.yml"
  DISTRIB_REVISION: "${testDistribRevision}"
  reclassVersion: "${reclassVersion}"
  """
    def chunkJob = build job: chunkJobName, parameters: [
        [$class: 'TextParameterValue', name: 'EXTRA_VARIABLES_YAML',
         value : _values_string.stripIndent()],
    ]
    // Put sub-job info into global map.
    testModelBuildsData.put(_uuid, ['jobname'  : chunkJob.fullProjectName,
                                    'copyToDir': "${artifactCopyPath}/${modelFile}",
                                    'buildId'  : "${chunkJob.number}"])
}

def StepTestModel(basename, reclassArtifactName, artifactCopyPath) {
    // We need to wrap what we return in a Groovy closure, or else it's invoked
    // when this method is called, not when we pass it to parallel.
    // To do this, you need to wrap the code below in { }, and either return
    // that explicitly, or use { -> } syntax.
    // return node object
    return {
        node(slaveNode) {
            testModel(basename, reclassArtifactName, artifactCopyPath)
        }
    }
}

def StepPrepareGit(templateEnvFolder, gerrit_data) {
    // return git clone  object
    return {
        def checkouted = false
        common.infoMsg("StepPrepareGit: ${gerrit_data}")
        // fetch needed sources
        dir(templateEnvFolder) {
            if (gerrit_data['gerritRefSpec']) {
                // Those part might be not work,in case manual var's pass
                def gerritChange = gerrit.getGerritChange(gerrit_data['gerritName'], gerrit_data['gerritHost'],
                    gerrit_data['GERRIT_CHANGE_NUMBER'], gerrit_data['credentialsId'])
                merged = gerritChange.status == "MERGED"
                if (!merged) {
                    checkouted = gerrit.gerritPatchsetCheckout(gerrit_data)
                } else {
                    // update global variable for pretty return from pipeline
                    alreadyMerged = true
                    common.successMsg("Change ${gerrit_data['GERRIT_CHANGE_NUMBER']} is already merged, no need to gate them")
                    error('change already merged')
                }
            } else {
                // Get clean HEAD
                gerrit_data['useGerritTriggerBuildChooser'] = false
                checkouted = gerrit.gerritPatchsetCheckout(gerrit_data)
                if (!checkouted) {
                    error("Failed to get repo:${gerrit_data}")
                }
            }
        }
    }
}

def StepGenerateModels(_contextFileList, _virtualenv, _templateEnvDir) {
    return {
        for (contextFile in _contextFileList) {
            generateModel(contextFile, _virtualenv, _templateEnvDir)
        }
    }
}

def globalVariatorsUpdate() {
    // Simple function, to check and define branch-around variables
    // In general, simply make transition updates for non-master branch
    // based on magic logic
    def message = ''
    if (env.GERRIT_PROJECT) {
        // TODO are we going to have such branches?
        if (!['nightly', 'testing', 'stable', 'proposed', 'master'].contains(env.GERRIT_BRANCH)) {
            gerritDataCC['gerritBranch'] = env.GERRIT_BRANCH
            gerritDataRS['gerritBranch'] = env.GERRIT_BRANCH
            // 'binary' branch logic w\o 'release/' prefix
            testDistribRevision = env.GERRIT_BRANCH.split('/')[-1]
            // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
            if (!common.checkRemoteBinary([apt_mk_version: testDistribRevision]).linux_system_repo_url) {
                common.errorMsg("Binary release: ${testDistribRevision} not exist. Fallback to 'proposed'! ")
                testDistribRevision = 'proposed'
            }
        }
        // Identify, who triggered. To whom we should pass refspec
        if (env.GERRIT_PROJECT == 'salt-models/reclass-system') {
            gerritDataRS['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataRS['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            message = "<br/>RECLASS_SYSTEM_GIT_REF =>${gerritDataRS['gerritRefSpec']}"
        } else if (env.GERRIT_PROJECT == 'mk/cookiecutter-templates') {
            gerritDataCC['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataCC['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            message = "<br/>COOKIECUTTER_TEMPLATE_REF =>${gerritDataCC['gerritRefSpec']}"
        } else {
            error("Unsuported gerrit-project triggered:${env.GERRIT_PROJECT}")
        }

        message = "<font color='red'>GerritTrigger detected! We are in auto-mode:</font>" +
            "<br/>Test env variables has been changed:" +
            "<br/>COOKIECUTTER_TEMPLATE_BRANCH => ${gerritDataCC['gerritBranch']}" +
            "<br/>DISTRIB_REVISION =>${testDistribRevision}" +
            "<br/>RECLASS_MODEL_BRANCH=> ${gerritDataRS['gerritBranch']}" + message + "<br/>"
        common.warningMsg(message)
        currentBuild.description = currentBuild.description ? message + "<br/>" + currentBuild.description : message
    } else {
        // Check for passed variables:
        gerritDataRS['gerritRefSpec'] = env.RECLASS_SYSTEM_GIT_REF ?: null
        gerritDataCC['gerritRefSpec'] = env.COOKIECUTTER_TEMPLATE_REF ?: null
        message = "<font color='red'>Non-gerrit trigger run detected!</font>" + "<br/>"
        currentBuild.description = currentBuild.description ? message + "<br/>" + currentBuild.description : message
    }
    gerritDataCCHEAD << gerritDataCC
    gerritDataCCHEAD['gerritRefSpec'] = null
    gerritDataCCHEAD['GERRIT_CHANGE_NUMBER'] = null
    gerritDataRSHEAD << gerritDataRS
    gerritDataRSHEAD['gerritRefSpec'] = null
    gerritDataRSHEAD['GERRIT_CHANGE_NUMBER'] = null

}

def replaceGeneratedValues(path) {
    def files = sh(script: "find ${path} -name 'secrets.yml'", returnStdout: true)
    def stepsForParallel = [:]
    stepsForParallel.failFast = true
    files.tokenize().each {
        stepsForParallel.put("Removing generated passwords/secrets from ${it}",
            {
                def secrets = readYaml file: it
                for (String key in secrets['parameters']['_param'].keySet()) {
                    secrets['parameters']['_param'][key] = 'generated'
                }
                // writeYaml can't write to already existing file
                writeYaml file: "${it}.tmp", data: secrets
                sh "mv ${it}.tmp ${it}"
            })
    }
    parallel stepsForParallel
}

def linkReclassModels(contextList, envPath, archiveName) {
    // to be able share reclass for all subenvs
    // Also, makes artifact test more solid - use one reclass for all of sub-models.
    // Archive Structure will be:
    // tar.gz
    // ├── contexts
    // │   └── ceph.yml
    // ├── ${reclassDirName} <<< reclass system
    // ├── model
    // │   └── ceph       <<< from `context basename`
    // │       ├── classes
    // │       │   ├── cluster
    // │       │   └── system -> ../../../${reclassDirName}
    // │       └── nodes
    // │           └── cfg01.ceph-cluster-domain.local.yml
    dir(envPath) {
        for (String context : contextList) {
            def basename = common.GetBaseName(context, '.yml')
            dir("${envPath}/model/${basename}") {
                sh(script: "mkdir -p classes/; ln -sfv ../../../../${common.GetBaseName(archiveName, '.tar.gz')} classes/system ")
            }
        }
        // replace all generated passwords/secrets/keys with hardcode value for infra/secrets.yaml
        replaceGeneratedValues("${envPath}/model")
        // Save all models and all contexts. Warning! `h` flag must be used!
        sh(script: "set -ex; tar -czhf ${env.WORKSPACE}/${archiveName} --exclude='*@tmp' model contexts", returnStatus: true)
    }
    archiveArtifacts artifacts: archiveName
}

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        globalVariatorsUpdate()
        def templateEnvHead = "${env.WORKSPACE}/EnvHead/"
        def templateEnvPatched = "${env.WORKSPACE}/EnvPatched/"
        def contextFileListHead = []
        def contextFileListPatched = []
        def vEnv = "${env.WORKSPACE}/venv"
        def headReclassArtifactName = "head_reclass.tar.gz"
        def patchedReclassArtifactName = "patched_reclass.tar.gz"
        def reclassNodeInfoDir = "${env.WORKSPACE}/reclassNodeInfo_compare/"
        def reclassInfoHeadPath = "${reclassNodeInfoDir}/old"
        def reclassInfoPatchedPath = "${reclassNodeInfoDir}/new"
        try {
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
            stage('Download and prepare CC env') {
                // Prepare 2 env - for patchset, and for HEAD
                def paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['downloadEnvHead'] = StepPrepareGit(templateEnvHead, gerritDataCCHEAD)
                if (gerritDataCC.get('gerritRefSpec', null)) {
                    paralellEnvs['downloadEnvPatched'] = StepPrepareGit(templateEnvPatched, gerritDataCC)
                    parallel paralellEnvs
                } else {
                    paralellEnvs['downloadEnvPatched'] = { common.warningMsg('No need to process: downloadEnvPatched') }
                    parallel paralellEnvs
                    sh("rsync -a --exclude '*@tmp' ${templateEnvHead} ${templateEnvPatched}")
                }
            }
            stage("Check workflow_definition") {
                // Check only for patchset
                python.setupVirtualenv(vEnv, 'python2', [], "${templateEnvPatched}/requirements.txt")
                if (gerritDataCC.get('gerritRefSpec', null)) {
                    common.infoMsg(python.runVirtualenvCommand(vEnv, "python ${templateEnvPatched}/workflow_definition_test.py"))
                } else {
                    common.infoMsg('No need to process: workflow_definition')
                }
            }

            stage("generate models") {
                dir("${templateEnvHead}/contexts") {
                    for (String x : findFiles(glob: "*.yml")) {
                        contextFileListHead.add(x)
                    }
                }
                dir("${templateEnvPatched}/contexts") {
                    for (String x : findFiles(glob: "*.yml")) {
                        contextFileListPatched.add(x)
                    }
                }
                // Generate over 2env's - for patchset, and for HEAD
                def paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['GenerateEnvHead'] = StepGenerateModels(contextFileListHead, vEnv, templateEnvHead)
                if (gerritDataCC.get('gerritRefSpec', null)) {
                    paralellEnvs['GenerateEnvPatched'] = StepGenerateModels(contextFileListPatched, vEnv, templateEnvPatched)
                    parallel paralellEnvs
                } else {
                    paralellEnvs['GenerateEnvPatched'] = { common.warningMsg('No need to process: GenerateEnvPatched') }
                    parallel paralellEnvs
                    sh("rsync -a --exclude '*@tmp' ${templateEnvHead} ${templateEnvPatched}")
                }

                // We need 2 git's, one for HEAD, one for PATCHed.
                // if no patch, use head for both
                RSHeadDir = common.GetBaseName(headReclassArtifactName, '.tar.gz')
                RSPatchedDir = common.GetBaseName(patchedReclassArtifactName, '.tar.gz')
                common.infoMsg("gerritDataRS= ${gerritDataRS}")
                common.infoMsg("gerritDataRSHEAD= ${gerritDataRSHEAD}")
                if (gerritDataRS.get('gerritRefSpec', null)) {
                    StepPrepareGit("${env.WORKSPACE}/${RSPatchedDir}/", gerritDataRS).call()
                    StepPrepareGit("${env.WORKSPACE}/${RSHeadDir}/", gerritDataRSHEAD).call()
                } else {
                    StepPrepareGit("${env.WORKSPACE}/${RSHeadDir}/", gerritDataRS).call()
                    sh("cd ${env.WORKSPACE} ; ln -svf ${RSHeadDir} ${RSPatchedDir}")
                }
                // link all models, to use one global reclass
                // For HEAD
                linkReclassModels(contextFileListHead, templateEnvHead, headReclassArtifactName)
                // For patched
                linkReclassModels(contextFileListPatched, templateEnvPatched, patchedReclassArtifactName)
            }

            stage("Compare cluster lvl Head/Patched") {
                // Compare patched and HEAD reclass pillars
                compareRoot = "${env.WORKSPACE}/cluster_compare/"
                sh(script: """
                   mkdir -pv ${compareRoot}/new ${compareRoot}/old
                   tar -xzf ${patchedReclassArtifactName}  --directory ${compareRoot}/new
                   tar -xzf ${headReclassArtifactName}  --directory ${compareRoot}/old
                   """)
                common.warningMsg('infra/secrets.yml has been skipped from compare!')
                result = '\n' + common.comparePillars(compareRoot, env.BUILD_URL, "-Ev \'infra/secrets.yml|\\.git\'")
                currentBuild.description = currentBuild.description ? currentBuild.description + result : result
            }
            stage("TestContexts Head/Patched") {
                def stepsForParallel = [:]
                stepsForParallel.failFast = true
                common.infoMsg("Found: ${contextFileListHead.size()} HEAD contexts to test.")
                for (String context : contextFileListHead) {
                    def basename = common.GetBaseName(context, '.yml')
                    stepsForParallel.put("ContextHeadTest:${basename}", StepTestModel(basename, headReclassArtifactName, reclassInfoHeadPath))
                }
                common.infoMsg("Found: ${contextFileListPatched.size()} patched contexts to test.")
                for (String context : contextFileListPatched) {
                    def basename = common.GetBaseName(context, '.yml')
                    stepsForParallel.put("ContextPatchedTest:${basename}", StepTestModel(basename, patchedReclassArtifactName, reclassInfoPatchedPath))
                }
                parallel stepsForParallel
                common.infoMsg('All TestContexts tests done')
            }
            stage("Compare NodesInfo Head/Patched") {
                // Download all artifacts
                def stepsForParallel = [:]
                stepsForParallel.failFast = true
                common.infoMsg("Found: ${testModelBuildsData.size()} nodeinfo artifacts to download.")
                testModelBuildsData.each { bname, bdata ->
                    stepsForParallel.put("FetchData:${bname}",
                        getAndUnpackNodesInfoArtifact(bdata.jobname, bdata.copyToDir, bdata.buildId))
                }
                parallel stepsForParallel
                // remove timestamp field from rendered files
                sh("find ${reclassNodeInfoDir} -type f -exec sed -i '/  timestamp: .*/d' {} \\;")
                // Compare patched and HEAD reclass pillars
                result = '\n' + common.comparePillars(reclassNodeInfoDir, env.BUILD_URL, '')
                currentBuild.description = currentBuild.description ? currentBuild.description + result : result
            }
            stage('Check include order') {
                if (!checkIncludeOrder) {
                    common.infoMsg('Check include order require to much time, and currently disabled!')

                } else {
                    def correctIncludeOrder = ["service", "system", "cluster"]
                    dir(reclassInfoPatchedPath) {
                        def nodeInfoFiles = findFiles(glob: "**/*.reclass.nodeinfo")
                        def messages = ["<b>Wrong include ordering found</b><ul>"]
                        def stepsForParallel = [:]
                        nodeInfoFiles.each { nodeInfo ->
                            stepsForParallel.put("Checking ${nodeInfo.path}:", {
                                def node = readYaml file: nodeInfo.path
                                def classes = node['classes']
                                def curClassID = 0
                                def prevClassID = 0
                                def wrongOrder = false
                                for (String className in classes) {
                                    def currentClass = className.tokenize('.')[0]
                                    curClassID = correctIncludeOrder.indexOf(currentClass)
                                    if (currentClass != correctIncludeOrder[prevClassID]) {
                                        if (prevClassID > curClassID) {
                                            wrongOrder = true
                                            common.warningMsg("File ${nodeInfo.path} contains wrong order of classes including: Includes for ${className} should be declared before ${correctIncludeOrder[prevClassID]} includes")
                                        } else {
                                            prevClassID = curClassID
                                        }
                                    }
                                }
                                if (wrongOrder) {
                                    messages.add("<li>${nodeInfo.path} contains wrong order of classes including</li>")
                                }
                            })
                        }
                        parallel stepsForParallel
                        def includerOrder = '<b>No wrong include order</b>'
                        if (messages.size() != 1) {
                            includerOrder = messages.join('')
                        }
                        currentBuild.description = currentBuild.description ? currentBuild.description + includerOrder : includerOrder
                    }
                }
            }
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')

        } catch (Throwable e) {
            if (alreadyMerged) {
                currentBuild.result = 'ABORTED'
                currentBuild.description = "Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them"
                return
            }
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            def dummy = "dummy"
        }
    }
}
