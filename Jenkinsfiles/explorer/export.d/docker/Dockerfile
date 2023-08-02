# vim:set ft=dockerfile:
ARG NUXEO_VERSION=2021

FROM docker-private.packages.nuxeo.com/nuxeo/nuxeo:${NUXEO_VERSION}

ARG VERSION
ARG SCM_REF
ARG BUILD_TAG

ARG EXPLORER_CONNECT_CLID
ARG EXPLORER_CONNECT_URL

ARG EXPORT_PACKAGE_CONNECT_CLID
ARG EXPORT_PACKAGE_CONNECT_URL
ARG EXPORT_PACKAGE_LIST

LABEL org.nuxeo.explorer.export.build-tag=${BUILD_TAG}
LABEL org.nuxeo.explorer.export.scm-ref=${SCM_REF}
LABEL org.nuxeo.explorer.export.version=${VERSION}

# install nuxeo-explorer package
RUN /install-packages.sh --clid "${EXPLORER_CONNECT_CLID}" --connect-url "${EXPLORER_CONNECT_URL}" "platform-explorer" >&1;
# then packages to take into account when exporting
RUN /install-packages.sh --clid "${EXPORT_PACKAGE_CONNECT_CLID}" --connect-url "${EXPORT_PACKAGE_CONNECT_URL}" "${EXPORT_PACKAGE_LIST//+/ }" >&1;
# then cleanup
RUN rm -rf $NUXEO_HOME/packages/backup

RUN NUXEO_CONF=$NUXEO_HOME/bin/nuxeo.conf $NUXEO_HOME/bin/nuxeoctl mp-list

# override start command to disable strict mode
CMD ["nuxeoctl", "console", "--lenient"]
