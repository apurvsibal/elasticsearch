/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.integration;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.rest.action.admin.indices.RestPutIndexTemplateAction;
import org.elasticsearch.xcontent.XContentBuilder;
import org.junit.Before;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class TransformPivotRestSpecialCasesIT extends TransformRestTestCase {
    private static boolean indicesCreated = false;

    // preserve indices in order to reuse source indices in several test cases
    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Before
    public void createIndexes() throws IOException {

        // it's not possible to run it as @BeforeClass as clients aren't initialized then, so we need this little hack
        if (indicesCreated) {
            return;
        }

        createReviewsIndex();
        indicesCreated = true;
    }

    public void testIndexTemplateMappingClash() throws Exception {
        String transformId = "special_pivot_template_mappings_clash";
        String transformIndex = "special_pivot_template_mappings_clash";

        // create a template that defines a field "rating" with a type "float" which will clash later with
        // output field "rating.avg" in the pivot config
        final Request createIndexTemplateRequest = new Request("PUT", "_template/special_pivot_template");

        String template = """
            {
              "index_patterns": [ "special_pivot_template*" ],
              "mappings": {
                "properties": {
                  "rating": {
                    "type": "float"
                  }
                }
              }
            }""";

        createIndexTemplateRequest.setJsonEntity(template);
        createIndexTemplateRequest.setOptions(expectWarnings(RestPutIndexTemplateAction.DEPRECATION_WARNING));
        Map<String, Object> createIndexTemplateResponse = entityAsMap(client().performRequest(createIndexTemplateRequest));
        assertThat(createIndexTemplateResponse.get("acknowledged"), equalTo(Boolean.TRUE));

        final Request createTransformRequest = new Request("PUT", getTransformEndpoint() + transformId);

        String config = formatted("""
            {
              "source": {
                "index": "%s"
              },
              "dest": {
                "index": "%s"
              },
              "pivot": {
                "group_by": {
                  "reviewer": {
                    "terms": {
                      "field": "user_id"
                    }
                  }
                },
                "aggregations": {
                  "rating.avg": {
                    "avg": {
                      "field": "stars"
                    }
                  }
                }
              }
            }""", REVIEWS_INDEX_NAME, transformIndex);

        createTransformRequest.setJsonEntity(config);
        Map<String, Object> createTransformResponse = entityAsMap(client().performRequest(createTransformRequest));
        assertThat(createTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));

        startAndWaitForTransform(transformId, transformIndex);
        assertTrue(indexExists(transformIndex));

        // we expect 27 documents as there shall be 27 user_id's
        Map<String, Object> indexStats = getAsMap(transformIndex + "/_stats");
        assertEquals(27, XContentMapValues.extractValue("_all.total.docs.count", indexStats));

        // get and check some users
        Map<String, Object> searchResult = getAsMap(transformIndex + "/_search?q=reviewer:user_4");

        assertEquals(1, XContentMapValues.extractValue("hits.total.value", searchResult));
        Number actual = (Number) ((List<?>) XContentMapValues.extractValue("hits.hits._source.rating.avg", searchResult)).get(0);
        assertEquals(3.878048780, actual.doubleValue(), 0.000001);
    }

    public void testSparseDataPercentiles() throws Exception {
        String indexName = "cpu-utilization";
        String transformIndex = "pivot-cpu";
        String transformId = "pivot-cpu";

        try (XContentBuilder builder = jsonBuilder()) {
            builder.startObject();
            {
                builder.startObject("mappings")
                    .startObject("properties")
                    .startObject("host")
                    .field("type", "keyword")
                    .endObject()
                    .startObject("cpu")
                    .field("type", "integer")
                    .endObject()
                    .endObject()
                    .endObject();
            }
            builder.endObject();
            final StringEntity entity = new StringEntity(Strings.toString(builder), ContentType.APPLICATION_JSON);
            Request req = new Request("PUT", indexName);
            req.setEntity(entity);
            client().performRequest(req);
        }

        final StringBuilder bulk = new StringBuilder();
        bulk.append(formatted("""
            {"index":{"_index":"%s"}}
            {"host":"host-1","cpu": 22}
            {"index":{"_index":"%s"}}
            {"host":"host-1","cpu": 55}
            {"index":{"_index":"%s"}}
            {"host":"host-1","cpu": 23}
            {"index":{"_index":"%s"}}
            {"host":"host-2","cpu": 0}
            {"index":{"_index":"%s"}}
            {"host":"host-2","cpu": 99}
            {"index":{"_index":"%s"}}
            {"host":"host-1","cpu": 28}
            {"index":{"_index":"%s"}}
            {"host":"host-1","cpu": 77}
            """, indexName, indexName, indexName, indexName, indexName, indexName, indexName));

        // missing value for cpu
        bulk.append(formatted("""
            {"index":{"_index":"%s"}}
            {"host":"host-3"}

            """, indexName));
        final Request bulkRequest = new Request("POST", "/_bulk");
        bulkRequest.addParameter("refresh", "true");
        bulkRequest.setJsonEntity(bulk.toString());
        client().performRequest(bulkRequest);

        final Request createTransformRequest = new Request("PUT", getTransformEndpoint() + transformId);

        String config = formatted("""
            {
              "source": {
                "index": "%s"
              },
              "dest": {
                "index": "%s"
              },
              "pivot": {
                "group_by": {
                  "host": {
                    "terms": {
                      "field": "host"
                    }
                  }
                },
                "aggregations": {
                  "p": {
                    "percentiles": {
                      "field": "cpu"
                    }
                  }
                }
              }
            }""", indexName, transformIndex);

        createTransformRequest.setJsonEntity(config);
        Map<String, Object> createTransformResponse = entityAsMap(client().performRequest(createTransformRequest));
        assertThat(createTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));

        startAndWaitForTransform(transformId, transformIndex);
        assertTrue(indexExists(transformIndex));

        Map<String, Object> indexStats = getAsMap(transformIndex + "/_stats");
        assertEquals(3, XContentMapValues.extractValue("_all.total.docs.count", indexStats));

        // get and check some data
        Map<String, Object> searchResult = getAsMap(transformIndex + "/_search?q=host:host-1");

        assertEquals(1, XContentMapValues.extractValue("hits.total.value", searchResult));
        @SuppressWarnings("unchecked")
        Map<String, Object> percentiles = (Map<String, Object>) ((List<?>) XContentMapValues.extractValue(
            "hits.hits._source.p",
            searchResult
        )).get(0);

        assertEquals(28.0, (double) percentiles.get("50"), 0.000001);
        assertEquals(77.0, (double) percentiles.get("99"), 0.000001);

        searchResult = getAsMap(transformIndex + "/_search?q=host:host-3");
        assertEquals(1, XContentMapValues.extractValue("hits.total.value", searchResult));

        @SuppressWarnings("unchecked")
        Map<String, Object> percentilesEmpty = (Map<String, Object>) ((List<?>) XContentMapValues.extractValue(
            "hits.hits._source.p",
            searchResult
        )).get(0);
        assertTrue(percentilesEmpty.containsKey("50"));
        assertNull(percentilesEmpty.get("50"));
        assertTrue(percentilesEmpty.containsKey("99"));
        assertNull(percentilesEmpty.get("99"));
    }

    /**
     * This test verifies that regardless of the max_page_search_size setting value used, the transform works correctly in the face of
     * restrictive bucket selector.
     * In the past there was a problem when there were no buckets (because bucket selector filtered them out) in a composite aggregation
     * page and for small enough max_page_search_size the transform stopped prematurely.
     * The problem was fixed by https://github.com/elastic/elasticsearch/pull/82852 and this test serves as a regression test for this PR.
     */
    public void testRestrictiveBucketSelector() throws Exception {
        String indexName = "special_pivot_bucket_selector_reviews";
        createReviewsIndex(indexName, 1000, 327, "date", false, 5, "affiliate_id");

        verifyDestIndexHitsCount(indexName, "special_pivot_bucket_selector-10", 10, 41);
        verifyDestIndexHitsCount(indexName, "special_pivot_bucket_selector-10000", 10000, 41);
    }

    private void verifyDestIndexHitsCount(String sourceIndex, String transformId, int maxPageSearchSize, long expectedDestIndexCount)
        throws Exception {
        String transformIndex = transformId;
        String config = formatted("""
            {
              "source": {
                "index": "%s"
              },
              "dest": {
                "index": "%s"
              },
              "frequency": "1m",
              "pivot": {
                "group_by": {
                  "user_id": {
                    "terms": {
                      "field": "user_id"
                    }
                  }
                },
                "aggregations": {
                  "stars_sum": {
                    "sum": {
                      "field": "stars"
                    }
                  },
                  "bs": {
                    "bucket_selector": {
                      "buckets_path": {
                        "stars_sum": "stars_sum.value"
                      },
                      "script": "params.stars_sum > 20"
                    }
                  }
                }
              },
              "settings": {
                "max_page_search_size": %s
              }
            }""", sourceIndex, transformIndex, maxPageSearchSize);
        Request createTransformRequest = new Request("PUT", getTransformEndpoint() + transformId);
        createTransformRequest.setJsonEntity(config);
        Map<String, Object> createTransformResponse = entityAsMap(client().performRequest(createTransformRequest));
        assertThat(createTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));
        startAndWaitForTransform(transformId, transformIndex);
        assertTrue(indexExists(transformIndex));
        Map<String, Object> searchResult = getAsMap(transformIndex + "/_search");
        long count = (Integer) XContentMapValues.extractValue("hits.total.value", searchResult);
        assertThat(count, is(equalTo(expectedDestIndexCount)));
    }
}
