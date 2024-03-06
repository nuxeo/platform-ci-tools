/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Anahide Tchertchian
 */
library identifier: "platform-ci-shared-library@v0.0.26"

/**
* Script cleaning up exports on target explorer site.
*
* Cleans up oldest distributions marked as "next" on beta explorer site, keeping only a given number of them.
* Allows avoiding piling up exports from nuxeo builds on explorer.beta.nuxeocloud.com.
* Triggered daily by a cron.
*/

boolean isExplorerBeta(explorerUrl) {
  return explorerUrl.contains("beta")
}

pipeline {
  agent {
    label 'jenkins-base'
  }
  environment {
    CURL_OPTIONS = "--location --connect-timeout 180 --max-time 300 --retry 2"
  }
  stages {
    stage('Set labels') {
      steps {
        container('base') {
          script {
            nxK8s.setPodLabels()
          }
        }
      }
    }
    stage('Check Parameters') {
      steps {
        script {
          def errorMessage = ""
          if (params.TARGET_URL.isEmpty() || params.TARGET_CREDS_ID.isEmpty()) {
            errorMessage = 'TARGET_URL and TARGET_CREDS_ID parameters are mandatory'
          } else if (!params.NUMBER_KEEP.isInteger() || params.NUMBER_KEEP.toInteger() < 0) {
            errorMessage = "Invalid number of exports '${params.NUMBER_KEEP}' (expecting integer >= 0)"
          }
          if (!errorMessage.isEmpty()) {
            currentBuild.result = 'ABORTED';
            currentBuild.description = "${errorMessage}"
            echo "${errorMessage}"
            error(currentBuild.description)
          }
        }
      }
    }
    stage('Cleanup exports') {
      steps {
        container('base') {
          script {
            echo """
            ----------------------------------------
            Cleanup Exports on ${params.TARGET_URL}
            ----------------------------------------"""
            withCredentials([usernameColonPassword(credentialsId: params.TARGET_CREDS_ID, variable: 'EXPLORER_PASS')]) {
              def curlCommand = 'curl --user $EXPLORER_PASS $CURL_OPTIONS'
              def query = "SELECT * FROM NXDistribution WHERE nxdistribution:aliases='next' ORDER BY dc:created ASC"
              def curlGet = "${curlCommand} -G --data-urlencode \"query=${query}\" --data-urlencode \"properties=nxdistribution\" ${params.TARGET_URL}/api/v1/search/lang/NXQL/execute"
              def responseGet = sh(script: curlGet, returnStdout: true).trim()
              def json = readJSON text: responseGet

              def num = json.entries.size()
              def numDeleted = 0
              def numToDelete = num - params.NUMBER_KEEP.toInteger()
              if (numToDelete > 0) {
                for (def entry: json.entries) {
                  echo "Deleting Nuxeo Platform ${entry.properties['nxdistribution:version']} export with document id ${entry.uid}"
                  // perform delete
                  sh(script: "${curlCommand} -X DELETE ${params.TARGET_URL}/api/v1/id/${entry.uid}")
                  numDeleted++
                  if (numDeleted >= numToDelete) {
                    break
                  }
                }
              }
              currentBuild.description = "Deleted ${numDeleted} out of ${num} exports"
              echo "${currentBuild.description}"
            }
          }
        }
      }
    }
  }
  post {
    unsuccessful {
      script {
        // TODO NXP-32209
        if (!isExplorerBeta(params.TARGET_URL)) {
          nxSlack.error(message: "Failed to cleanup old exports on ${params.TARGET_URL}: <${BUILD_URL}|#${BUILD_NUMBER}>")
        }
      }
    }
  }
}
