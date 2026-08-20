package com.knowagent.knowledge.infrastructure.vector;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.List;
import java.util.Objects;

/**
 * {@link MilvusClientAccess} backed by the Milvus Java SDK V2 {@link MilvusClientV2}.
 * Pure delegation - all timing, timeout and error-mapping behavior lives in the
 * adapter/executor, not here.
 */
final class SdkMilvusClientAccess implements MilvusClientAccess {

    private final MilvusClientV2 client;

    SdkMilvusClientAccess(MilvusClientV2 client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public boolean hasCollection(String collectionName) {
        return client.hasCollection(HasCollectionReq.builder().collectionName(collectionName).build());
    }

    @Override
    public void createCollection(CreateCollectionReq request) {
        client.createCollection(request);
    }

    @Override
    public void createCollectionIndex(String collectionName, IndexParam index, long timeoutMillis) {
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(index))
                .sync(true)
                .timeout(timeoutMillis)
                .build());
    }

    @Override
    public DescribeCollectionResp describeCollection(String collectionName) {
        return client.describeCollection(DescribeCollectionReq.builder().collectionName(collectionName).build());
    }

    @Override
    public DescribeIndexResp describeIndex(String collectionName) {
        // The SDK filters the server response by the requested field name and throws
        // "Index not found" when the filter is empty, so the vector field must be set.
        return client.describeIndex(DescribeIndexReq.builder()
                .collectionName(collectionName)
                .fieldName(MilvusVectorStoreAdapter.VECTOR_FIELD)
                .build());
    }

    @Override
    public void loadCollection(String collectionName, long timeoutMillis) {
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .sync(true)
                .timeout(timeoutMillis)
                .build());
    }

    @Override
    public UpsertResp upsert(String collectionName, List<JsonObject> rows) {
        return client.upsert(UpsertReq.builder().collectionName(collectionName).data(rows).build());
    }

    @Override
    public SearchResp search(SearchReq request) {
        return client.search(request);
    }

    @Override
    public DeleteResp delete(String collectionName, String filter) {
        return client.delete(DeleteReq.builder().collectionName(collectionName).filter(filter).build());
    }
}
