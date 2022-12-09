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

def getOpenshiftClusters() {
  return [
    'oh': [
      'registry': 'docker-registry-default.apps.oh.nuxeocloud.com/common-infra',
      'url': 'https://openshift.oh.nuxeocloud.com',
    ],
    'uk': [
      'registry': 'docker-registry-default.apps.uk.nuxeocloud.com/common-infra',
      'url': 'http://openshift.uk.nuxeocloud.com',
    ],
    'va': [
      'registry': 'docker-registry-default.apps.va.nuxeocloud.com/common-infra',
      'url': 'https://openshift.va.nuxeocloud.com',
    ],
  ]
}

def getOpenshiftClusterKeys() {
  return openshiftClusters.keySet();
}

def getOpenshiftRegistry(key) {
  return openshiftClusters.get(key).get('registry')
}

def getOpenshiftURL(key) {
    return openshiftClusters.get(key).get('url')
}

return this
