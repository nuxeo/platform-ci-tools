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
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 *     Kevin Leturc <kleturc@nuxeo.com>
 *     Anahide Tchertchian
 */
import hudson.model.Result

library identifier: "platform-ci-shared-library@v0.0.55"

// package list to use when exporting a build version of Nuxeo Platform
DEFAULT_PACKAGE_LIST = [
  'easyshare',
  'nuxeo-csv',
  'nuxeo-drive',
  'nuxeo-imap-connector',
  'nuxeo-multi-tenant',
  'nuxeo-platform-importer',
  'nuxeo-quota',
  'nuxeo-signature',
  'nuxeo-template-rendering',
  'shibboleth-authentication',
  'nuxeo-liveconnect',
  'nuxeo-platform-3d',
]
// package list to append to the default one when exporting a promoted version of Nuxeo Platform
ADDITIONAL_PACKAGE_LIST = [
  'cas2-authentication',
  'nuxeo-diff',
  'nuxeo-platform-user-registration',
  'nuxeo-virtualnavigation',
  'nuxeo-web-ui',
  'nuxeo-jsf-ui',
  'nuxeo-arender',
  'nuxeo-aspera',
  'nuxeo-coldstorage',
  'nuxeo-retention',
  'nuxeo-salesforce',
]

String retrieveNuxeoVersion() {
  container('base') {
    for (def runWrapper : currentBuild.upstreamBuilds) {
      if (runWrapper.fullProjectName.startsWith('nuxeo/lts/nuxeo/')) {
        // parse description which should look like "Build 2021.22.4" or "Build 2021.22.4: comment"
        def matcher = (runWrapper.description =~ /Build\s(\d+\.\d+\.\d+).*/)
        if (matcher.matches()) {
          return matcher.group(1)
        }
      }
    }
    return "${params.NUXEO_VERSION}"
  }
}

boolean isNuxeoPromoted(nuxeoVersion) {
  return nuxeoVersion.equals(nxUtils.getMajorDotMinorVersion(version: nuxeoVersion))
}

pipeline {
  agent {
    label 'jenkins-base'
  }
  environment {
    CURRENT_NAMESPACE = nxK8s.getCurrentNamespace()

    NUXEO_VERSION = retrieveNuxeoVersion()

    EXPLORER_CONNECT_CLID_ID = 'instance-clid'
    EXPLORER_CONNECT_URL = "${CONNECT_PROD_SITE_URL}"

    EXPORT_PACKAGE_CONNECT_CLID_ID = "${isNuxeoPromoted(env.NUXEO_VERSION) ? 'instance-clid': 'instance-clid-preprod'}"
    EXPORT_PACKAGE_CONNECT_URL = "${isNuxeoPromoted(env.NUXEO_VERSION) ? CONNECT_PROD_SITE_URL : CONNECT_PREPROD_SITE_URL}"
    EXPORT_PACKAGE_LIST = "${DEFAULT_PACKAGE_LIST.join(' ')} ${isNuxeoPromoted(env.NUXEO_VERSION) ? ADDITIONAL_PACKAGE_LIST.join(' ') : ''}".trim()
    EXPORT_SNAPSHOT_NAME = "Nuxeo Platform"

    VERSION = "${NUXEO_VERSION}-latest"

    UPLOAD_URL = "${isNuxeoPromoted(env.NUXEO_VERSION) ? 'https://explorer.nuxeo.com/nuxeo' : 'https://explorer.beta.nuxeocloud.com/nuxeo'}"
    UPLOAD_CREDS_ID = "${isNuxeoPromoted(env.NUXEO_VERSION) ? 'explorer-prod' : 'explorer-beta-nco'}"

    CURL_OPTIONS = "--location --connect-timeout 180 --max-time 300 --retry 2"
  }
  stages {
    stage('Set Labels') {
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
          echo """
          ----------------------------------------
          Nuxeo version:                 '${NUXEO_VERSION}'

          Explorer Connect URL:          '${EXPLORER_CONNECT_URL}'
          Export Package Connect URL:    '${EXPORT_PACKAGE_CONNECT_URL}'
          Package List:                  '${EXPORT_PACKAGE_LIST}'

          Upload URL:                    '${UPLOAD_URL}'
          ----------------------------------------
          """
          if (env.NUXEO_VERSION.isEmpty()) {
            error('NUXEO_VERSION parameter is required')
          }
          currentBuild.description = "Generating reference export for ${NUXEO_VERSION}"
        }
      }
    }
    stage('Build Docker Image') {
      steps {
        container('base') {
          script {
            withCredentials([string(credentialsId: EXPLORER_CONNECT_CLID_ID, variable: 'EXPLORER_CONNECT_CLID_VALUE'),
                             string(credentialsId: EXPORT_PACKAGE_CONNECT_CLID_ID, variable: 'EXPORT_PACKAGE_CONNECT_CLID_VALUE')]) {
              // replace lines by "--"
              def oneLineExplorerClid = sh(
                returnStdout: true,
                script: '''#!/bin/bash +x
                  echo -e "$EXPLORER_CONNECT_CLID_VALUE" | sed ':a;N;\$!ba;s/\\n/--/g'
                '''
              )
              def oneLineExportClid = sh(
                returnStdout: true,
                script: '''#!/bin/bash +x
                  echo -e "$EXPORT_PACKAGE_CONNECT_CLID_VALUE" | sed ':a;N;\$!ba;s/\\n/--/g'
                '''
              )
              withEnv(["EXPLORER_CONNECT_CLID=${oneLineExplorerClid}", "EXPORT_PACKAGE_CONNECT_CLID=${oneLineExportClid}"]) {
                nxDocker.build(skaffoldFile: 'Jenkinsfiles/explorer/export.d/docker/skaffold.yaml')
              }
            }
          }
        }
      }
    }

    stage('Perform Export') {
      steps {
        container('base') {
          echo """
          ----------------------------------------
          Deploy Explorer export environment
          ----------------------------------------"""
          nxWithHelmfileDeployment(file: 'Jenkinsfiles/explorer/export.d/helm/helmfile.yaml', secrets: [[name: 'instance-clid', namespace: 'platform']]) {
            script {
              sh "mkdir -p target"
              String nuxeoUrl = "nuxeo.${NAMESPACE}.svc.cluster.local/nuxeo"
              String explorerUrl = "${nuxeoUrl}/site/distribution"
              echo """
              ----------------------------------------
              Perform export on ${explorerUrl}
              ----------------------------------------
              """
              String distribId = "${EXPORT_SNAPSHOT_NAME}-${NUXEO_VERSION}".replaceAll(" ", "%20")
              String curlCommand = "curl --user Administrator:Administrator ${CURL_OPTIONS}"
              retry (2) {
                sh """
                  ${curlCommand} -d 'name=${EXPORT_SNAPSHOT_NAME}' -d 'version=${NUXEO_VERSION}' -H 'Accept: text/plain' ${explorerUrl}/save
                  ${curlCommand} ${explorerUrl} --output target/home_after_save.html
                  ${curlCommand} ${nuxeoUrl}/site/automation/Elasticsearch.WaitForIndexing -H 'Content-Type:application/json' -X POST -d '{"params":{"timeoutSecond": "3600", "refresh": "true", "waitForAudit": "true"},"context":{}}'
                """
              }
              retry (2) {
                // export.zip is the format needed for beta/prod upload
                // json format could be retrieved too on /site/distribution/DISTRIBUTION_ID/json
                sh "${curlCommand} ${explorerUrl}/download/${distribId} --output target/export.zip"
              }
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/home*.html, **/export.zip, **/requirements.lock, **/charts/*.tgz, **/target/**/*.yaml'
        }
      }
    }
    stage('Upload Snapshot to Explorer') {
      steps {
        container('base') {
          script {
            echo """
            ----------------------------------------
            Upload Export to ${UPLOAD_URL}
            ----------------------------------------"""
            def aliases = isNuxeoPromoted(env.NUXEO_VERSION) ? 'latest' : "next\n${nxUtils.getMajorDotMinorVersion(version: env.NUXEO_VERSION)}.x"
            nxUtils.postForm(credentialsId: env.UPLOAD_CREDS_ID, url: "${UPLOAD_URL}/site/distribution/uploadDistrib",
                form: ['snap=@target/export.zip', "\$'nxdistribution:aliases=${aliases}'"])
          }
        }
      }
    }
  }
  post {
    always {
      script {
        nxJira.updateIssues()
        if (isNuxeoPromoted(env.NUXEO_VERSION)) {
          def currentResult = Result.fromString(currentBuild.result)
          if ((currentResult == Result.SUCCESS || currentResult == Result.UNSTABLE)
              && utils.previousBuildStatusIs(status: Result.FAILURE, ignoredStatuses: [Result.ABORTED, Result.NOT_BUILT])) {
            nxTeams.success(
                message: "Successfully uploaded nuxeo-explorer reference export for ${NUXEO_VERSION}",
                changes: true,
            )
          } else if (currentResult == Result.FAILURE) {
            nxTeams.error(
                message: "Failed to upload nuxeo-explorer reference export for ${NUXEO_VERSION}",
                changes: true,
                culprits: true,
            )
          }
        }
      }
    }
  }
}
