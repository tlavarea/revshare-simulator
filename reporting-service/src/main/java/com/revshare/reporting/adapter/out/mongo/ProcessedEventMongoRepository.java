package com.revshare.reporting.adapter.out.mongo;

import com.revshare.reporting.adapter.out.mongo.document.ProcessedEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Event ids the projector has already applied. */
@Repository
public interface ProcessedEventMongoRepository extends MongoRepository<ProcessedEventDocument, String> {}
