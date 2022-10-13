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

pipeline {
  agent {
    label 'jenkins-base'
  }
  environment {
    DOCUMENT_FOUNDATION_REPOSITORY = 'https://packages.nuxeo.com/repository/document-foundation-raw'

    LIBREOFFICE_VERSION = "${params.LIBREOFFICE_VERSION}"
    LIBREOFFICE_TARBALL = "LibreOffice_${LIBREOFFICE_VERSION}_Linux_x86-64_rpm.tar.gz"
  }
  stages {
    stage('Download and upload Libreoffice') {
      steps {
        container('base') {
          echo """
          ------------------------------------------------
          Download Libreoffice from documentfoundation.org
          ------------------------------------------------"""
          sh 'curl --fail -L https://download.documentfoundation.org/libreoffice/stable/$LIBREOFFICE_VERSION/rpm/x86_64/$LIBREOFFICE_TARBALL --output $LIBREOFFICE_TARBALL'

          echo """
          ------------------------------------------------
          Upload Libreoffice to packages.nuxeo.com
          ------------------------------------------------"""
          withCredentials([usernameColonPassword(credentialsId: 'packages.nuxeo.com-auth', variable: 'PACKAGES_PASS')]) {
            sh 'curl -i --fail -u $PACKAGES_PASS --upload-file $LIBREOFFICE_TARBALL "$DOCUMENT_FOUNDATION_REPOSITORY/$LIBREOFFICE_TARBALL"'
          }
        }
      }
    }
  }
}
