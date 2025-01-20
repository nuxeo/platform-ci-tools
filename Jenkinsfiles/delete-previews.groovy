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
 */
library identifier: "platform-ci-shared-library@v0.0.41"

NAMESPACE_SUFFIX_PATTERN = '-preview'

pipeline {
  agent {
    label 'jenkins-base'
  }
  stages {
    stage('Delete previews') {
      steps {
        container('base') {
          script {
            echo "Looking for Kubernetes namespaces ending with ${NAMESPACE_SUFFIX_PATTERN}:"
            def namespaces
            try {
              def escapedPattern = NAMESPACE_SUFFIX_PATTERN.replaceAll('-', '\\\\-')
              namespaces = sh(
                returnStdout: true,
                script: "kubectl get ns -oname | grep -E '${escapedPattern}\$'"
              ).trim()
            } catch(err) {
              echo 'Found no Kubernetes namespaces'
              return
            }
            echo namespaces
            if (nxUtils.isDryRun()) {
              echo "[DRY RUN]: Don't delete Kubernetes namespaces"
            } else {
              echo 'Delete Kubernetes namespaces'
              sh "echo ${namespaces.replaceAll('\\n', ' ')} | xargs kubectl delete"
            }
          }
        }
      }
    }
  }
}
