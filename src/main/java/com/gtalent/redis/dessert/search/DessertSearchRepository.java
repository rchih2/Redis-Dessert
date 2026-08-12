package com.gtalent.redis.dessert.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Elasticsearch Repository，供基本的 save / deleteById / deleteAll 使用。
 * 較複雜的模糊/範圍查詢改用 {@link DessertSearchQueryService} 透過
 * {@code ElasticsearchOperations} + {@code CriteriaQuery} 組裝，Repository 這裡只負責寫入面。
 */
@Repository
public interface DessertSearchRepository extends ElasticsearchRepository<DessertSearchDocument, Long> {
}