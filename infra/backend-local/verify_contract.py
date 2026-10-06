#!/usr/bin/env python3
"""Compare the live Node and Spring catalog contracts using only stdlib."""

import json
import urllib.error
import urllib.request


NODE = "http://127.0.0.1:18081"
SPRING_A = "http://127.0.0.1:18082"
SPRING_B = "http://127.0.0.1:18083"
EDGE = "http://127.0.0.1:18080"
FIELDS = {
    "productId", "productType", "displayName", "description", "priceGold",
    "packSize", "weightN", "weightR", "weightSr", "weightUr", "poolMode",
    "pool", "accessoryType", "accessoryId", "sortOrder",
}


def call(base, path="/v1/shop/catalog", method="GET"):
    request = urllib.request.Request(base + path, method=method)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            raw = response.read()
            return response.status, response.headers, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, error.headers, json.loads(raw) if raw else None


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    catalog_path = "/v1/shop/catalog?contract=1"
    node_status, _, node = call(NODE, catalog_path)
    spring_a_status, spring_a_headers, spring_a = call(SPRING_A, catalog_path)
    spring_b_status, spring_b_headers, spring_b = call(SPRING_B, catalog_path)

    require(node_status == spring_a_status == spring_b_status == 200, "catalog status differs")
    require(node == spring_a == spring_b, "Node and Spring A/B catalog JSON differ")
    require(node["revision"] == 2, "fixture revision is not the explicit expected value 2")
    require(node["products"], "seed catalog must not be empty")
    require(node["products"][0]["productId"] == "basic_pack", "fixture order changed")
    require(all(set(product) == FIELDS for product in node["products"]), "public field set changed")
    require(all("enabled" not in product for product in node["products"]), "enabled leaked")
    require(spring_a_headers.get("X-Instance-Id") == "spring-a", "Spring A identity differs")
    require(spring_b_headers.get("X-Instance-Id") == "spring-b", "Spring B identity differs")

    edge_instances = set()
    for _ in range(10):
        edge_status, edge_headers, edge = call(EDGE, catalog_path)
        require(edge_status == 200, "Nginx catalog status differs")
        require(edge == node, "Nginx catalog JSON differs")
        edge_instances.add(edge_headers.get("X-Instance-Id"))
    expected_instances = {"spring-a", "spring-b"}
    require(
        expected_instances.issubset(edge_instances),
        f"Nginx did not reach both Spring instances: {edge_instances}",
    )

    for method, expected in (("POST", 405), ("HEAD", 405), ("OPTIONS", 204)):
        require(call(NODE, method=method)[0] == expected, f"Node {method} status changed")
        require(call(SPRING_A, method=method)[0] == expected, f"Spring {method} status differs")

    require(call(EDGE, "/v1/health")[0] == 200, "Nginx Node health route failed")
    print("PASS: Spring A/B match the Node catalog and both answer through Nginx")


if __name__ == "__main__":
    main()
