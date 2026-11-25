"""Check that the public OpenAPI and runtime authorization inventory agree."""

import json
import unittest
from pathlib import Path


CONTRACT = Path(__file__).resolve().parents[1] / "src/main/resources/contract"


def read_contract(name):
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


class PublicApiContractTest(unittest.TestCase):
    def test_public_operations_have_one_path_and_match_the_inventory(self):
        spec = read_contract("mobile-openapi.json")
        routes = read_contract("api-inventory.json")["routes"]
        routes += read_contract("extensions/sam3d.json")["routes"]
        runtime = [
            (route["method"].lower(), route["path"])
            for route in routes
            if route["path"].startswith("/api/")
            and not route["path"].startswith("/api/v1/internal/")
        ]
        documented = {
            (method, path)
            for path, operations in spec["paths"].items()
            for method, operation in operations.items()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertEqual(len(runtime), len(set(runtime)))
        self.assertEqual(set(runtime), documented)
        self.assertTrue(all(not path.startswith("/api/v1/") for _, path in runtime))
        for operations in spec["paths"].values():
            for operation in operations.values():
                if not isinstance(operation, dict):
                    continue
                for policy in operation.get("security", []):
                    self.assertLessEqual(
                        policy.keys(), spec["components"]["securitySchemes"].keys()
                    )

    def test_capture_and_agent_share_the_public_path_by_media_type(self):
        paths = read_contract("mobile-openapi.json")["paths"]
        for path in ("/api/listings/{listingId}/captures", "/api/furniture/{furnitureId}/captures"):
            with self.subTest(path=path):
                self.assertEqual(
                    set(paths[path]["post"]["requestBody"]["content"]),
                    {"application/zip", "application/json"},
                )
        self.assertEqual(
            set(paths["/api/users/me/agent-profile"]["post"]["requestBody"]["content"]),
            {"multipart/form-data", "application/json"},
        )
