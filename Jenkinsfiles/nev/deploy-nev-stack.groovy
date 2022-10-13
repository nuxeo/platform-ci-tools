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
 *     Kevin Leturc <kleturc@nuxeo.com>
 */

NUXEO_APB_CATALOG = 'nuxeo-apb-catalog'
NUXEO_GITHUB_ORGANIZATION = 'https://github.com/nuxeo/'

def cloneRepo(name, branch = 'master', relativePath = name) {
  checkout([$class: 'GitSCM',
    branches: [[name: branch]],
    browser: [$class: 'GithubWeb', repoUrl: "${NUXEO_GITHUB_ORGANIZATION}${name}"],
    doGenerateSubmoduleConfigurations: false,
    extensions: [
      [$class: 'RelativeTargetDirectory', relativeTargetDir: relativePath],
      [$class: 'WipeWorkspace'],
      [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false, timeout: 60],
      [$class: 'CheckoutOption', timeout: 60],
      [$class: 'LocalBranch']
    ],
    submoduleCfg: [],
    userRemoteConfigs: [[credentialsId: 'jx-pipeline-git-github-git', url: "${NUXEO_GITHUB_ORGANIZATION}${name}"]]
  ])
}

def deployNEVStack(part, openshiftEnv) {
  echo """
  ----------------------------------------
  Deploy the NEV ${part} to ${OPENSHIFT_CLUSTER_KEY}/${openshiftEnv}
  ----------------------------------------
  """
  dir("nuxeo-arender-${part}-apb") {
    withCredentials([file(credentialsId: "openshift-${OPENSHIFT_CLUSTER_KEY}-${openshiftEnv}-${part}-config", variable: 'APB_CONFIG')]) {
      sh 'mv ${APB_CONFIG} ./config.json.tosubst'
      sh 'envsubst < config.json.tosubst > config.json'
      // remove TTY from docker run as we don't have a TTY in jenkins
      sh 'sed -e "s/-ti/-i/" -i Makefile'
      // add the openshift kubeconfig to apb command
      sh 'sed -e "s%apb prepare%apb --kubeconfig=${OPENSHIFT_KUBE_CONFIG}/config prepare%" -i Makefile'
      // replace the .kube path mounted into the container by the Openshift one, because /root is not shared between
      // the openshift and the dind containers
      sh 'sed -e "s%\\${HOME}/.kube%${OPENSHIFT_KUBE_CONFIG}%" -i Makefile'
      sh 'make provision'
    }
  }
}

def lib

pipeline {
  agent {
    label 'nuxeo-openshift'
  }
  environment {
    FROM_REGISTRY = "${params.FROM_REGISTRY}"
    NEV_VERSION = "${params.NEV_VERSION}"
    OPENSHIFT_CLUSTER_KEY = "${params.TO_ENVIRONMENT.replaceFirst('/.*', '')}"
    OPENSHIFT_KUBE_CONFIG = '/home/jenkins/.kube-oc'
    TO_ENVIRONMENT = "${params.TO_ENVIRONMENT}" // has the following form: io/dev, va/qa
  }
  stages {
    stage('Pull/Push Docker') {
      when {
        expression {
          return params.PUSH_DOCKER_IMAGES
        }
      }
      steps {
        echo """
        ----------------------------------------
        Trigger Docker Images pull/push
        ----------------------------------------
        """
        script {
          def parameters = [
            string(name: 'NEV_VERSION', value: env.NEV_VERSION),
            string(name: 'FROM_REGISTRY', value: env.FROM_REGISTRY),
            string(name: 'TO_CLUSTER', value: env.OPENSHIFT_CLUSTER_KEY),
            booleanParam(name: 'LEGACY', value: false),
          ]
          build(
            job: "nev/pull-push-docker-image",
            parameters: parameters,
            wait: true
          )
        }
      }
    }

    stage('Deploy the NEV stack') {
      steps {
        container('openshift') {
          cloneRepo(NUXEO_APB_CATALOG)
          script {
            lib = load 'Jenkinsfiles/nev/lib.groovy'
            def openshiftEnv = env.TO_ENVIRONMENT.replaceFirst('.*/', '')
            dir(NUXEO_APB_CATALOG) {
              // login to the Openshift cluster as jenkins-platform service account
              env.OPENSHIFT_URL = lib.getOpenshiftURL(env.OPENSHIFT_CLUSTER_KEY)
              withCredentials([string(credentialsId: "openshift-${OPENSHIFT_CLUSTER_KEY}-kubectl", variable: 'TOKEN')]) {
                sh 'oc login ${OPENSHIFT_URL} --token=${TOKEN} --kubeconfig=${OPENSHIFT_KUBE_CONFIG}/config'
              }
              deployNEVStack("rendition", openshiftEnv)
              deployNEVStack("previewer", openshiftEnv)
              // retrieve Nuxeo URL for upstream job
              withCredentials([file(credentialsId: "openshift-${OPENSHIFT_CLUSTER_KEY}-${openshiftEnv}-previewer-config", variable: 'APB_CONFIG')]) {
                env.NEV_NUXEO_URL =  sh(returnStdout: true, script: 'cat ${APB_CONFIG} | jq .previewer_nuxeo_url | sed \'s/"//g\'')
                env.OPENSHIFT_ENV_URL =  env.OPENSHIFT_URL + '/console/project/' + sh(returnStdout: true, script: 'cat ${APB_CONFIG} | jq .namespace | sed \'s/"//g\'')
              }
            }
          }
        }
      }
    }
  }
}
