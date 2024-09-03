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

library identifier: "platform-ci-shared-library@v0.0.38"

GITHUB_URL = 'https://github.com'
NUXEO_ORGANIZATION = 'nuxeo'
NUXEO_REPOSITORY = 'nuxeo'
NUXEO_REPOSITORY_URL = "${GITHUB_URL}/${NUXEO_ORGANIZATION}/${NUXEO_REPOSITORY}"
NUXEO_LTS_REPOSITORY = 'nuxeo-lts'
NUXEO_LTS_REPOSITORY_URL = "${GITHUB_URL}/${NUXEO_ORGANIZATION}/${NUXEO_LTS_REPOSITORY}"
NUXEO_LTS_BRANCH = "${params.NUXEO_LTS_BRANCH}"
DELAY_DAYS = 90

def setJobNaming() {
  currentBuild.displayName = "#${currentBuild.number} (${NUXEO_LTS_BRANCH})"
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
          script {
            setJobNaming()
            nxGit.cloneRepository(name: NUXEO_REPOSITORY, branch: NUXEO_LTS_BRANCH, shallow: true, noTags: true)
            dir(NUXEO_REPOSITORY) {
              sh "git remote add ${NUXEO_LTS_REPOSITORY} ${NUXEO_LTS_REPOSITORY_URL}.git"
              nxGit.fetch(remote: NUXEO_LTS_REPOSITORY, reference: NUXEO_LTS_BRANCH)
              def upperDate = getUpperDate()
              def upperRevision = getUpperRevision(upperDate)
              if (!nxUtils.isDryRun()) {
                sh "git merge ${upperRevision}"
                nxGit.push(reference: NUXEO_LTS_BRANCH)
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
        nxJira.updateIssues()
      }
    }
    success {
      script {
        if (!hudson.model.Result.SUCCESS.toString().equals(currentBuild.getPreviousBuild()?.getResult())) {
          nxSlack.success(message: "Successfully mirrored ${NUXEO_LTS_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} to ${NUXEO_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} ${BUILD_URL}")
        }
      }
    }
    unsuccessful {
      script {
        if (![hudson.model.Result.ABORTED.toString(), hudson.model.Result.NOT_BUILT.toString()].contains(currentBuild.result)) {
          nxSlack.error(message: "Failed to mirror ${NUXEO_LTS_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} to ${NUXEO_REPOSITORY_URL}/tree/${NUXEO_LTS_BRANCH} ${BUILD_URL}")
        }
      }
    }
  }
}
