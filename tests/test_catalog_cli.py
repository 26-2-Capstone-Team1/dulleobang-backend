import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from app.cli import ITEM_FILES, articles, download_catalog


class CatalogCliTest(unittest.TestCase):
    def test_index_forms_preserve_article(self):
        self.assertEqual(articles(["00123456"]), ["00123456"])
        self.assertEqual(articles({"items": [{"article": "001.234.56"}]}), ["001.234.56"])
        self.assertEqual(articles({"articles": []}), [])

    def test_invalid_paths_and_duplicates_are_rejected(self):
        for index in (["../00123456"], ["a/b"], ["a\\b"], [".."], [1], ["a", "a"], {}):
            with self.subTest(index=index), self.assertRaises(ValueError):
                articles(index)

    def test_downloads_only_the_release_files(self):
        payload = {"ikea/v1/index.json": json.dumps({"items": ["00123456"]}).encode()}
        payload.update({f"ikea/v1/00123456/{name}": name.encode() for name in ITEM_FILES})

        def blob(key):
            return Mock(
                download_as_bytes=lambda **_: payload[key],
                download_to_filename=lambda filename, **_: Path(filename).write_bytes(payload[key]),
            )

        bucket = Mock(blob=Mock(side_effect=blob))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            download_catalog(bucket, "/ikea/v1/", root)
            for name in ITEM_FILES:
                self.assertEqual((root / "00123456" / name).read_bytes(), name.encode())
        self.assertEqual([call.args[0] for call in bucket.blob.call_args_list], list(payload))
