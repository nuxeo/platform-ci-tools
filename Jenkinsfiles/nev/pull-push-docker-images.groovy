/*
 * (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
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
def lib

pipeline {
  agent {
    label 'jenkins-base'
  }
  environment {
    FROM_REGISTRY = "${params.FROM_REGISTRY}"
    NEV_VERSION = "${params.NEV_VERSION}"
    TO_CLUSTER = "${params.TO_CLUSTER}"
  }
  stages {
    stage('Pull/Push Docker') {
      steps {
        container('base') {
          script {
            lib = load 'Jenkinsfiles/nev/lib.groovy'

            def images = [];
            // before repository split, Nuxeo and ARender Docker images haven't the same tag
            if (params.LEGACY.toBoolean()) {
              def previewerImage = "${FROM_REGISTRY}/nuxeo/arender-previewer:${NEV_VERSION}";
              def arenderVersion = sh(returnStdout: true, script: "skopeo inspect docker://${previewerImage} | jq '.Labels.\"com.nuxeo.arender.arender-version\"'").replaceAll('"', '').trim();

              images.add("nuxeo/arender-previewer:${NEV_VERSION}");
              images.add("arender-document-converter:$arenderVersion");
              images.add("arender-document-renderer:$arenderVersion");
              images.add("arender-document-service-broker:$arenderVersion");
              images.add("arender-document-text-handler:$arenderVersion");
            } else {
              images.add("nuxeo/arender-ui:${NEV_VERSION}");
              images.add("nuxeo/arender-document-converter:${NEV_VERSION}");
              images.add("nuxeo/arender-document-renderer:${NEV_VERSION}");
              images.add("nuxeo/arender-document-service-broker:${NEV_VERSION}");
              images.add("nuxeo/arender-document-text-handler:${NEV_VERSION}");
            }

            def clusters = [];
            if ("${TO_CLUSTER}" == '*') {
              clusters.addAll(lib.getOpenshiftClusterKeys());
            } else {
              clusters.add("${TO_CLUSTER}".trim());
            }
            def dockerCfgDir = "/home/jenkins/skopeo"
            sh "mkdir -p $dockerCfgDir"

            clusters.each { cluster ->
              withCredentials([file(credentialsId: "openshift-$cluster-dockercfg", variable: 'DOCKER_CFG')]) {
                def dockerCfg = "$dockerCfgDir/dockercfg-${cluster}.json"
                sh "mv \${DOCKER_CFG} ${dockerCfg}"

                images.each { fromImage ->
                  def fullFromImage = "${FROM_REGISTRY}/$fromImage";
                  
                  def fullToImage = "${lib.getOpenshiftRegistry(cluster)}/$fromImage";
                  if (params.LEGACY.toBoolean()) {
                    fullToImage = fullToImage.replaceAll('nuxeo\\/', '');
                  } else {
                    fullToImage = fullToImage.replaceAll('nuxeo\\/', 'nuxeo-');
                  }

                  sh "skopeo copy --dest-authfile ${dockerCfg} --dest-tls-verify=false docker://${fullFromImage} docker://${fullToImage}"
                }
              }
            }
          }
        }
      }
    }
  }
}
