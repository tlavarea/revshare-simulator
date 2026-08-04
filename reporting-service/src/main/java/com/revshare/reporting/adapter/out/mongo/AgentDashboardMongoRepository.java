package com.revshare.reporting.adapter.out.mongo;

import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Dashboards by agent id.
 *
 * <p>No query methods beyond the inherited ones, and that is the point of the read model: the document is assembled so
 * that serving it is a primary key lookup. A finder here that scanned or aggregated would be a sign the projection is
 * missing something the caller needs.
 */
@Repository
public interface AgentDashboardMongoRepository extends MongoRepository<AgentDashboardDocument, String> {}
