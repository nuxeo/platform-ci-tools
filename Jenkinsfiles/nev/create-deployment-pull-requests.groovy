/*
 * (C) Copyright 2023 Nuxeo (http://nuxeo.com/) and others.
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
 *     Kevin Leturc <kleturc@nuxeo.com>
 */
library identifier: "platform-ci-shared-library@v0.0.27"

ARENDER_DEPLOYMENT = 'arender-deployment'
ARENDER_DEPLOYMENT_BASE_BRANCH = 'main'

FROM_DOCKER_REGISTRY = "${params.FROM_DOCKER_REGISTRY}"
NEV_CHART_VERSION = "${params.NEV_CHART_VERSION}"
NEV_DOCKER_VERSION = "${params.NEV_DOCKER_VERSION}"
TARGET_ENVIRONMENT = "${params.TARGET_ENVIRONMENT}"

def createDeploymentPullRequest(Map args = [:]) {
  // repositoryProject corresponds to the terraform deployment, it could be either dev, uat/backend, uat/nev-2023...
  def repositoryProject = args.environment
  if (args.project) {
    repositoryProject += "/${args.project}"
  }
  echo "Creating Pull Request for repository project: ${repositoryProject}"
  // create a working branch
  def branchNameSuffix = NEV_DOCKER_VERSION ? "docker-to-${NEV_DOCKER_VERSION}" : "chart-to-${NEV_CHART_VERSION}"
  def branchName = "task-upgrade-${repositoryProject.replace('/', '-')}-${branchNameSuffix}"
  sh "git checkout ${ARENDER_DEPLOYMENT_BASE_BRANCH}"
  sh "git checkout -b ${branchName}"
  // edit terraform.tfvars file to upgrade NEV version
  def terraformVarsFile = "deploy/${repositoryProject}/terraform.tfvars"
  def message
  if (NEV_DOCKER_VERSION) {
    sh "sed -i 's/NEV_DOCKER_VERSION = \".*\"/NEV_DOCKER_VERSION = \"${NEV_DOCKER_VERSION}\"/' ${terraformVarsFile}"
    if (FROM_DOCKER_REGISTRY) {
      sh "sed -i 's/NEV_DOCKER_REGISTRY = \".*\"/NEV_DOCKER_REGISTRY = \"${FROM_DOCKER_REGISTRY}\"/' ${terraformVarsFile}"
    }
    message = "Update ${repositoryProject} NEV Docker version to ${NEV_DOCKER_VERSION}"
  } else if (NEV_CHART_VERSION) {
    sh "sed -i 's/NEV_CHART_VERSION = \".*\"/NEV_CHART_VERSION = \"${NEV_CHART_VERSION}\"/' ${terraformVarsFile}"
    message = "Update ${repositoryProject} NEV Chart version to ${NEV_CHART_VERSION}"
  }
  // commit, push and create a pull request
  nxGit.commitPush(message: message, branch: branchName)
  nxGitHub.createPullRequest(base: ARENDER_DEPLOYMENT_BASE_BRANCH, title: message, body: message)
}

pipeline {
  agent {
    label 'jenkins-base'
  }
  stages {
    stage('Set labels') {
      steps {
        container('base') {
          script {
            nxK8s.setPodLabels(team: 'connectors')
          }
        }
      }
    }
    stage('Check parameters') {
      steps {
        script {
          if (!NEV_CHART_VERSION && !NEV_DOCKER_VERSION) {
            error('NEV_CHART_VERSION or NEV_DOCKER_VERSION parameter is required')
          } else if (NEV_CHART_VERSION && NEV_DOCKER_VERSION) {
            error('NEV_CHART_VERSION and NEV_DOCKER_VERSION parameters can not be used together')
          }
        }
      }
    }
    stage('Create Pull Requests') {
      steps {
        container('base') {
          script {
            nxGit.cloneRepository(name: ARENDER_DEPLOYMENT, branch: ARENDER_DEPLOYMENT_BASE_BRANCH)
            dir(ARENDER_DEPLOYMENT) {
              if (TARGET_ENVIRONMENT == "dev") {
                createDeploymentPullRequest(environment: TARGET_ENVIRONMENT)
              } else if (TARGET_ENVIRONMENT == "uat") {
                // arender-deployment GitHub action expects to have only one environment change per commit to deploy it
                for (def project : ['backend', 'nev-2019', 'nev-2021', 'nev-2023']) {
                  createDeploymentPullRequest(environment: TARGET_ENVIRONMENT, project: project)
                }
              }
            }
          }
        }
      }
    }
  }
}
