/**
 * Gerrit gating pipeline
 * CREDENTIALS_ID - Gerrit credentails ID
 * JOBS_NAMESPACE - Gerrit gating jobs namespace (mk, contrail, ...)
 *
**/

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
node("python") {
  try{
    stage("test") {
      if (!SKIP_TEST.equals("true")){
        wrap([$class: 'AnsiColorBuildWrapper']) {
          def gerritProjectArray = GERRIT_PROJECT.tokenize("/")
          def gerritProject = gerritProjectArray[gerritProjectArray.size() - 1]
          def jobsNamespace = JOBS_NAMESPACE
          // remove plural s on the end of job namespace
          if (JOBS_NAMESPACE[JOBS_NAMESPACE.length() - 1].equals("s")){
            jobsNamespace = JOBS_NAMESPACE.substring(0, JOBS_NAMESPACE.length() - 1)
          }
          def testJob = String.format("test-%s-%s", jobsNamespace, gerritProject)
          if (_jobExists(testJob)) {
            common.infoMsg("Test job ${testJob} found, running")
            build job: testJob, parameters: [
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git"],
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: GERRIT_REFSPEC]
            ]
          } else {
            common.infoMsg("Test job ${testJob} not found")
          }
        }
      } else {
        common.infoMsg("Test job skipped")
      }
    }
    stage("submit review"){
      ssh.prepareSshAgentKey(CREDENTIALS_ID)
      ssh.ensureKnownHosts(GERRIT_HOST)
      ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review --submit %s,%s", GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
      common.infoMsg(String.format("Gerrit review %s,%s submitted", GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     //common.sendNotification(currentBuild.result,"",["slack"])
  }
}

@NonCPS
def _jobExists(jobName){
  return Jenkins.instance.items.find{it -> it.name.equals(jobName)}
}
