package com.knowagent.knowledge.infrastructure.vector;

import com.google.gson.JsonObject;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.List;

/**
 * Narrow SDK seam the vector adapter and initializer depend on. It keeps the SDK
 * usage surface small, makes the adapter unit-testable without a live server and
 * keeps Milvus SDK types inside this infrastructure package (the
 * {@link com.knowagent.knowledge.vector.VectorStoreGateway} port never sees them).
 */
interface MilvusClientAccess {

    boolean hasCollection(String collectionName);

    void createCollection(CreateCollectionReq request);

    /** Creates the vector index synchronously (waits for the build) so the collection
     *  is immediately searchable and describeIndex never races an in-flight build. */
    void createCollectionIndex(String collectionName, IndexParam index, long timeoutMillis);

    DescribeCollectionResp describeCollection(String collectionName);

    DescribeIndexResp describeIndex(String collectionName);

    void loadCollection(String collectionName, long timeoutMillis);

    UpsertResp upsert(String collectionName, List<JsonObject> rows);

    SearchResp search(SearchReq request);

    DeleteResp delete(String collectionName, String filter);
}
