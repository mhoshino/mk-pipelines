/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   BATCH_SIZE                 Use batching for large amount of target nodes
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def minions
def result
def command
def commandKwargs

def batch_size = ''
if (common.validInputParam('BATCH_SIZE')) {
    batch_size = "${BATCH_SIZE}"
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('List target servers') {
                minions = salt.getMinions(pepperEnv, TARGET_SERVERS)

                if (minions.isEmpty()) {
                    throw new Exception("No minion was targeted")
                }

                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
                common.infoMsg("Selected nodes: ${targetLiveAll}")
            }

            stage("Trusty workaround") {
                if(salt.getGrain(pepperEnv, minions[0], "oscodename")['return'][0].values()[0]["oscodename"] == "trusty") {
                    common.infoMsg("First node %nodename% has trusty")
                    common.infoMsg("Assuming trusty on all cluster, running extra network states...")
                    common.infoMsg("Network iteration #1. Bonding")
                    salt.enforceState(pepperEnv, targetLiveAll, 'linux.network', true, true, batch_size)
                    common.infoMsg("Network iteration #2. Vlan tagging and bridging")
                    salt.enforceState(pepperEnv, targetLiveAll, 'linux.network', true, true, batch_size)
                }
            }

            stage("Setup repositories") {
                salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo', true, true, batch_size)
            }

            stage("Upgrade packages") {
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'pkg.upgrade', [], batch_size, true)
            }

            stage("Update Hosts file") {
                salt.enforceState(pepperEnv, "I@linux:system", 'linux.network.host', true, true, batch_size)
            }

            stage("Setup networking") {
                // Sync all of the modules from the salt master.
                salt.syncAll(pepperEnv, targetLiveAll, batch_size)

                // Apply state 'salt' to install python-psutil for network configuration without restarting salt-minion to avoid losing connection.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['salt', 'exclude=[{\'id\': \'salt_minion_service\'}, {\'id\': \'salt_minion_service_restart\'}, {\'id\': \'salt_minion_sync_all\'}]'], batch_size, true)

                // Restart salt-minion to take effect.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['salt-minion'], batch_size, true, 10)

                // Configure networking excluding vhost0 interface.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['linux.network', 'exclude=[{\'id\': \'linux_interface_vhost0\'}]'], batch_size, true)

                // Kill unnecessary processes ifup/ifdown which is stuck from previous state linux.network.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifup'], batch_size, false)
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifdown'], batch_size, false)

                // Restart networking to bring UP all interfaces.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['networking'], batch_size, true, 300)
            }

            stage("Highstate compute") {
                // Execute highstate without state opencontrail.client.
                common.retry(2){
                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.highstate', ['exclude=opencontrail.client'], batch_size, true)
                }

                // Apply nova state to remove libvirt default bridge virbr0.
                salt.enforceState(pepperEnv, targetLiveAll, 'nova', true, true, batch_size)

                // Execute highstate.
                salt.enforceHighstate(pepperEnv, targetLiveAll, true, true, batch_size)

                // Restart supervisor-vrouter.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['supervisor-vrouter'], batch_size, true, 300)

                // Apply salt and collectd if is present to update information about current network interfaces.
                salt.enforceState(pepperEnv, targetLiveAll, 'salt', true, true, batch_size)
                if(!salt.getPillar(pepperEnv, minions[0], "collectd")['return'][0].values()[0].isEmpty()) {
                    salt.enforceState(pepperEnv, targetLiveAll, 'collectd', true, true, batch_size)
                }
            }

        stage("Update/Install monitoring") {
            //Collect Grains
            salt.enforceState(pepperEnv, targetLiveAll, 'salt.minion.grains', true, true, batch_size)
            salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'saltutil.refresh_modules', [], batch_size)
            salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'mine.update', [], batch_size)
            sleep(5)

            salt.enforceState(pepperEnv, targetLiveAll, 'prometheus', true, true, batch_size)
            salt.enforceState(pepperEnv, 'I@prometheus:server', 'prometheus', true, true, batch_size)
        }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
