/*
 * (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
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
 *     Thomas Fowley <thomas.fowley@hyland.com>
 */

nuxeo2021PipelineURL = "https://jenkins.platform.dev.nuxeo.com/job/nuxeo/job/lts/job/nuxeo/job/2021/"
nuxeoJsfUi2021PipelineURL = "https://jenkins.platform.dev.nuxeo.com/job/nuxeo/job/lts/job/nuxeo-jsf-ui/job/2021/"
nuxeoHf2021PipelineURL = "https://jenkins.platform.dev.nuxeo.com/job/nuxeo/job/lts/job/nuxeo-hf-2021/"

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: repositoryUrl],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

def getNextBuildNumber(pipelineURL){
  sh """
  curl --silent ${pipelineURL}/api/json | grep -Po '"nextBuildNumber":\\K\\d+'
  """
}

def getBuildState(pipelineURL){
  buildNumber = getNextBuildNumber(pipelineURL)
  sh """
  curl --silent ${pipelineURL}/${buildNumber}/api/json | grep -Po '"result":\\s*"\\K\\w+'
  """
}

pipeline {

  agent {
    label 'jenkins-nuxeo-package-lts-2021'  // change this?
  }

  options {
    timeout(time: 30, unit: 'MINUTES')
  }

  environment {
    SLACK_CHANNEL = 'testing' // change to platform notifs
  }

  stages {

    stage('Send Slack Notification') {
      steps {
        script {
          def hfState = getBuildState(nuxeoHf2021PipelineURL)
          def nuxeo2021State = getBuildState(nuxeo2021PipelineURL)
          def jsfUiState = getBuildState(nuxeoJsfUi2021PipelineURL)

          def platformMessage = """
            Dear devs, the daily check on important LTS 2021 pipelines is complete :tada:
            Results are as follows:
            <${nuxeo2021PipelineURL}|Nuxeo 2021> status = ${nuxeo2021State}
            <${nuxeoHf2021PipelineURL}|Nuxeo Hotfix 2021> status = ${hfState}
            <${nuxeoJsfUi2021PipelineURL}|Nuxeo JSF UI 2021> status = ${jsfUiState}
          """

          def colour = 'good'
          if ((nuxeo2021State || hfState || jsfUiState) == "FAILURE") {
            colour = 'danger'
          }

          slackSend(channel: "${SLACK_CHANNEL}", color: ${colour}, message: ${platformMessage})
        }
      }
    }
  }

  post {
    unsuccessful {
      script {
        if (env.DRY_RUN != 'true') {
          slackSend(channel: "${SLACK_CHANNEL}", color: 'danger', message: "Failed to check important LTS 2021 pipelines")
        }
      }
    }
  }

}