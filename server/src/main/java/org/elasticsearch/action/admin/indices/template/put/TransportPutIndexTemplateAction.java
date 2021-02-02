/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.action.admin.indices.template.put;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * Put index template action.
 */
public class TransportPutIndexTemplateAction extends AcknowledgedTransportMasterNodeAction<PutIndexTemplateRequest> {

    private static final Logger logger = LogManager.getLogger(TransportPutIndexTemplateAction.class);

    private final MetadataIndexTemplateService indexTemplateService;
    private final IndexScopedSettings indexScopedSettings;

    @Inject
    public TransportPutIndexTemplateAction(TransportService transportService, ClusterService clusterService,
                                           ThreadPool threadPool, MetadataIndexTemplateService indexTemplateService,
                                           ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                           IndexScopedSettings indexScopedSettings) {
        super(PutIndexTemplateAction.NAME, transportService, clusterService, threadPool, actionFilters,
            PutIndexTemplateRequest::new, indexNameExpressionResolver, ThreadPool.Names.SAME);
        this.indexTemplateService = indexTemplateService;
        this.indexScopedSettings = indexScopedSettings;
    }

    @Override
    protected ClusterBlockException checkBlock(PutIndexTemplateRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void masterOperation(final PutIndexTemplateRequest request, final ClusterState state,
                                   final ActionListener<AcknowledgedResponse> listener) {
        String cause = request.cause();
        if (cause.length() == 0) {
            cause = "api";
        }
        final Settings.Builder templateSettingsBuilder = Settings.builder();
        templateSettingsBuilder.put(request.settings()).normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX);
        indexScopedSettings.validate(templateSettingsBuilder.build(), true); // templates must be consistent with regards to dependencies
        indexTemplateService.putTemplate(new MetadataIndexTemplateService.PutRequest(cause, request.name())
                .patterns(request.patterns())
                .order(request.order())
                .settings(templateSettingsBuilder.build())
                .mappings(request.mappings())
                .aliases(request.aliases())
                .create(request.create())
                .masterTimeout(request.masterNodeTimeout())
                .version(request.version()),

                new MetadataIndexTemplateService.PutListener() {
                    @Override
                    public void onResponse(MetadataIndexTemplateService.PutResponse response) {
                        listener.onResponse(AcknowledgedResponse.of(response.acknowledged()));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.debug(() -> new ParameterizedMessage("failed to put template [{}]", request.name()), e);
                        listener.onFailure(e);
                    }
                });
    }
}
