/*
 * (C) Copyright 2025 Nuxeo (http://nuxeo.com/) and others.
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
 *     Antoine Taillefer <antoine.taillefer@hyland.com>
 */
library identifier: "platform-ci-shared-library@v0.0.68"

String getLatestPackageVersion(name, ltsVersion) {
  withCredentials([usernameColonPassword(credentialsId: 'connect-prod', variable: 'CONNECT_AUTH')]) {
    def url = "https://connect.nuxeo.com/nuxeo/site/nos-marketplace/orgs/nuxeo/packages/${name}/last/lts-${ltsVersion}"
    echo "Marketplace URL to fetch the latest ${name} package for ${ltsVersion} = ${url}"

    def latestPackage = sh(
      returnStdout: true,
      script: 'curl --fail -u $CONNECT_AUTH ' + url
    )
    echo "Latest ${name} package = ${latestPackage}"

    def version = latestPackage.replaceAll('"', '').tokenize(':')[1]
    echo "Latest ${name} package version = ${version}"
    return version
  }
}

String getPreviewNamespace(branchName) {
  branchName = branchName ?: 'main'
  def namespace = "${nxK8s.getCurrentNamespace()}-nev-${branchName}-preview".replaceAll('\\.', '-').toLowerCase()
  def aboveKubernetesLimit = namespace.length() - 63
  // ingress host also has the 63 limit per segment, add back the 'nuxeo-' length part
  def aboveIngressLimit = aboveKubernetesLimit + 6
  if (aboveIngressLimit > 0) {
    branchName = branchName.substring(0, branchName.length() - aboveIngressLimit)
    namespace = "${nxK8s.getCurrentNamespace()}-nev-${branchName}-preview".replaceAll('\\.', '-').toLowerCase()
  }
  return namespace
}

String getVersionFromLatestTag(repository) {
  return nxGit.getRepositoryLatestTag(repository: repository).replace('v', '')
}

void logVersions() {
  echo "NEV_CHART_VERSION = ${NEV_CHART_VERSION}"
  echo "ARENDER_NUXEO_VERSION = ${ARENDER_NUXEO_VERSION}"
  echo "NUXEO_ARENDER_CONNECTOR_VERSION = ${NUXEO_ARENDER_CONNECTOR_VERSION}"
}

String applyConfigMap() {
  sh "envsubst < 'Jenkinsfiles/nev/deploy-preview.d/data/configmap.yaml' | kubectl --namespace=${PREVIEW_NAMESPACE} apply -f -"
}

boolean isPRVersion(version) {
  return version =~ /^.*-PR-.*$/
}

String getMarkdownVersions() {
  return """
- NEV Helm chart: `${NEV_CHART_VERSION}`
- ARender Nuxeo: `${ARENDER_NUXEO_VERSION}`
- Nuxeo Helm chart: `${NUXEO_CHART_VERSION}`
- Nuxeo: `${NUXEO_VERSION}`
- Nuxeo ARender connector: `${NUXEO_ARENDER_CONNECTOR_VERSION}`
"""
}

pipeline {
  agent {
    label "jenkins-base"
  }
  environment {
    NUXEO_CHART_VERSION = '~3.1.8'
    NUXEO_DEFAULT_VERSION = '2023'
    PREVIEW_NAMESPACE = getPreviewNamespace(params.BRANCH_NAME)
    VERSIONING_CONFIGMAP = 'nev-preview'
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
    stage('Init versions') {
      steps {
        container('base') {
          script {
            def namespaceExists = sh(
              returnStatus: true,
              script: "kubectl get namespace ${PREVIEW_NAMESPACE}"
            ) == 0
            if (!namespaceExists) {
              echo "Create preview namespace: ${PREVIEW_NAMESPACE}"
              sh "kubectl create namespace ${PREVIEW_NAMESPACE}"
              
              echo "Compute versions from job parameters and default values"
              env.NEV_CHART_VERSION = params.NEV_CHART_VERSION ?: getVersionFromLatestTag('https://github.com/nuxeo/arender-helm-chart.git')
              env.ARENDER_NUXEO_VERSION = params.ARENDER_NUXEO_VERSION ?: getVersionFromLatestTag('https://github.com/nuxeo/arender-nuxeo.git')
              env.NUXEO_ARENDER_CONNECTOR_VERSION = params.NUXEO_ARENDER_CONNECTOR_VERSION ?: getLatestPackageVersion('nuxeo-arender', NUXEO_DEFAULT_VERSION)
              logVersions()

              echo "Create versioning ConfigMap"
              applyConfigMap()
            } else {
              echo "Found existing preview namespace: ${PREVIEW_NAMESPACE}"
              
              echo "Compute versions from job parameters and ConfigMap"
              env.NEV_CHART_VERSION = params.NEV_CHART_VERSION ?:
                nxK8s.getConfigMapData(namespace: PREVIEW_NAMESPACE, name: VERSIONING_CONFIGMAP, key: 'nevChartVersion')
              env.ARENDER_NUXEO_VERSION = params.ARENDER_NUXEO_VERSION ?:
                nxK8s.getConfigMapData(namespace: PREVIEW_NAMESPACE, name: VERSIONING_CONFIGMAP, key: 'arenderNuxeoVersion')
              env.NUXEO_ARENDER_CONNECTOR_VERSION = params.NUXEO_ARENDER_CONNECTOR_VERSION ?:
                nxK8s.getConfigMapData(namespace: PREVIEW_NAMESPACE, name: VERSIONING_CONFIGMAP, key: 'nuxeoArenderConnectorVersion')
              logVersions()
              
              echo "Update versioning ConfigMap"
              applyConfigMap()
            }
            env.NEV_CHART_REPO = isPRVersion(NEV_CHART_VERSION)
              ? 'https://chartmuseum.platform.dev.nuxeo.com/'
              : 'https://packages.nuxeo.com/repository/helm-arender/'
            echo "NEV_CHART_REPO = ${NEV_CHART_REPO}"

            env.ARENDER_NUXEO_REGISTRY = isPRVersion(ARENDER_NUXEO_VERSION)
              ? DOCKER_REGISTRY
              : ARENDER_DOCKER_REGISTRY
            echo "ARENDER_NUXEO_REGISTRY = ${ARENDER_NUXEO_REGISTRY}"

            if (isPRVersion(NUXEO_ARENDER_CONNECTOR_VERSION)) {
              env.NUXEO_REGISTRY = DOCKER_REGISTRY
              env.NUXEO_IMAGE = 'nuxeo/nuxeo-arender-connector'
              env.NUXEO_VERSION = NUXEO_ARENDER_CONNECTOR_VERSION
            } else {
              env.NUXEO_REGISTRY = PRIVATE_DOCKER_REGISTRY
              env.NUXEO_IMAGE = 'nuxeo/nuxeo'
              env.NUXEO_VERSION = nxUtils.getMajorVersion(version: NUXEO_ARENDER_CONNECTOR_VERSION)
            }
            echo "NUXEO_REGISTRY = ${NUXEO_REGISTRY}"
            echo "NUXEO_IMAGE = ${NUXEO_IMAGE}"
            echo "NUXEO_VERSION = ${NUXEO_VERSION}"
          }
        }
      }
    }
    stage('Deploy Preview') {
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'preview', message: 'Deploy preview') {
            script {
              echo """
              ----------------------------------------
              Deploy NEV preview:
              - NEV Helm chart: ${NEV_CHART_VERSION}
              - ARender Nuxeo: ${ARENDER_NUXEO_VERSION}
              - Nuxeo Helm chart: ${NUXEO_CHART_VERSION}
              - Nuxeo: ${NUXEO_VERSION}
              - Nuxeo ARender connector: ${NUXEO_ARENDER_CONNECTOR_VERSION}
              ----------------------------------------"""
              def packagesUsername = nxK8s.getSecretData(namespace: 'platform', name: 'packages.nuxeo.com-auth', key: 'username')
              def packagesPassword = nxK8s.getSecretData(namespace: 'platform', name: 'packages.nuxeo.com-auth', key: 'password')
              def envVars = ["NEV_CHART_REPO_USERNAME=${packagesUsername}","NEV_CHART_REPO_PASSWORD=${packagesPassword}"]
              def helmfile = 'Jenkinsfiles/nev/deploy-preview.d/helm/helmfile.yaml'
              nxHelmfile.template(
                file: helmfile,
                namespace: PREVIEW_NAMESPACE,
                envVars: envVars,
                outputDir: 'target'
              )
              nxHelmfile.deploy(
                file: helmfile,
                namespace: PREVIEW_NAMESPACE,
                envVars: envVars,
                secrets: [[namespace: 'platform', name: 'instance-clid'], [namespace: 'platform', name: 'platform-tls']]
              )
              def host = sh(returnStdout: true, script: """
                kubectl get ingress nuxeo \
                  --namespace=${PREVIEW_NAMESPACE} \
                  -ojsonpath='{.spec.rules[*].host}'
              """)
              env.NEV_PREVIEW_URL = "https://${host}"
              echo """
              -----------------------------------------------
              NEV preview available at: ${NEV_PREVIEW_URL}
              -----------------------------------------------"""
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/**/*.yaml'
        }
      }
    }
  }
  post {
    always {
      script {
        currentBuild.description = "NEV chart ${NEV_CHART_VERSION}/ARender ${ARENDER_NUXEO_VERSION}/NEV package ${NUXEO_ARENDER_CONNECTOR_VERSION}"
      }
    }
    success {
      script {
        env.PR_COMMENT = """
NEV preview has been deployed with the following versions:

${getMarkdownVersions()}

You can access Nuxeo [here](${NEV_PREVIEW_URL}).
"""
      }
    }
    unsuccessful {
      script {
        env.PR_COMMENT = """
Failed to deploy NEV preview with the following versions:

${getMarkdownVersions()}

You can access the deployment job artifacts [here](${BUILD_URL}artifact/).
"""
      }
    }
  }
}
