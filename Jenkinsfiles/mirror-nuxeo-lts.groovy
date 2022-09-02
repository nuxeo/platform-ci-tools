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
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
import java.time.LocalDate

GITHUB_URL = 'https://github.com'
NUXEO_ORGANIZATION = 'nuxeo'
NUXEO_REPOSITORY = 'nuxeo'
NUXEO_REPOSITORY_URL = "${GITHUB_URL}/${NUXEO_ORGANIZATION}/${NUXEO_REPOSITORY}"
NUXEO_LTS_REPOSITORY = 'nuxeo-lts'
NUXEO_LTS_REPOSITORY_URL = "${GITHUB_URL}/${NUXEO_ORGANIZATION}/${NUXEO_LTS_REPOSITORY}"
NUXEO_LTS_BRANCH = "${params.NUXEO_LTS_BRANCH}"
DELAY_DAYS = 90
SLACK_CHANNEL = 'platform-notifs'

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: "${GITHUB_URL}/${NUXEO_ORGANIZATION}/platform-ci-tools/"],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

def isDryRun() {
  return DRY_RUN == 'true'
}

def cloneRepo(url, branch, relativePath, shallow = false, noTags = false) {
  checkout([$class: 'GitSCM',
    branches: [[name: branch]],
    browser: [$class: 'GithubWeb', repoUrl: url],
    doGenerateSubmoduleConfigurations: false,
    extensions: [
      [$class: 'RelativeTargetDirectory', relativeTargetDir: relativePath],
      [$class: 'WipeWorkspace'],
      [$class: 'CloneOption', depth: 0, noTags: noTags, reference: '', shallow: shallow, timeout: 60],
      [$class: 'CheckoutOption', timeout: 60],
      [$class: 'LocalBranch']
    ],
    submoduleCfg: [],
    userRemoteConfigs: [[credentialsId: 'jx-pipeline-git-github-git', url: url]]
  ])
}

def getToday() {
  return LocalDate.now()
}

def getFormattedDate(date) {
  return "${date.getYear()}-${date.getMonthValue().toString().padLeft(2, '0')}-${date.getDayOfMonth().toString().padLeft(2, '0')}"
}

def getUpperDate() {
  def today = getToday()
  echo "Today = ${today}"
  echo "Delay in days = ${DELAY_DAYS}"
  def upperDate = getFormattedDate(today.minusDays(DELAY_DAYS))
  echo "Upper date = ${upperDate}"
  return upperDate
}

def getUpperRevision(upperDate) {
  def upperRevision = sh(
    returnStdout: true,
    script: "git rev-list --until=${upperDate} -n 1 ${NUXEO_LTS_REPOSITORY}/${NUXEO_LTS_BRANCH}"
  )
  echo "Upper revision = ${upperRevision}"
  return upperRevision
}

pipeline {
  agent {
    label 'jenkins-base'
  }
  options {
    timeout(time: 1, unit: 'HOURS')
  }
  stages {
    stage('Mirror') {
      steps {
        container('base') {
          cloneRepo(NUXEO_REPOSITORY_URL, NUXEO_LTS_BRANCH, NUXEO_REPOSITORY, true, true)
          dir(NUXEO_REPOSITORY) {
            sh "git remote add ${NUXEO_LTS_REPOSITORY} ${NUXEO_LTS_REPOSITORY_URL}.git"
            sh """
              jx step git credentials
              git config credential.helper store
              git fetch ${NUXEO_LTS_REPOSITORY} ${NUXEO_LTS_BRANCH}
            """
            script {
              def upperDate = getUpperDate()
              def upperRevision = getUpperRevision(upperDate)
              if (!isDryRun()) {
                sh "git merge ${upperRevision}"
                sh "git push origin ${NUXEO_LTS_BRANCH}"
              } else {
                // Dry run
                sh "git diff --name-only origin/${NUXEO_LTS_BRANCH} ${upperRevision}"
              }
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        if (!isDryRun()) {
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
        }
      }
    }
    success {
      script {
        if (!isDryRun()
          && !hudson.model.Result.SUCCESS.toString().equals(currentBuild.getPreviousBuild()?.getResult())) {
          slackSend(channel: "${SLACK_CHANNEL}", color: 'good', message: "Successfully mirrored ${NUXEO_LTS_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} to ${NUXEO_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} ${BUILD_URL}")
        }
      }
    }
    unsuccessful {
      script {
        if (!isDryRun()
          && ![hudson.model.Result.ABORTED.toString(), hudson.model.Result.NOT_BUILT.toString()].contains(currentBuild.result)) {
          slackSend(channel: "${SLACK_CHANNEL}", color: 'danger', message: "Failed to mirror ${NUXEO_LTS_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} to ${NUXEO_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} ${BUILD_URL}")
        }
      }
    }
  }
}
