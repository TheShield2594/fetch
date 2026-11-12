import unittest

from locale_coverage import folder_for


class FolderForTest(unittest.TestCase):
    def test_plain_language(self):
        self.assertEqual("values-fr", folder_for("fr"))

    def test_region(self):
        self.assertEqual("values-pt-rBR", folder_for("pt-BR"))
        self.assertEqual("values-zh-rTW", folder_for("zh-TW"))

    def test_numeric_region_and_script_use_bcp47_folders(self):
        self.assertEqual("values-b+es+419", folder_for("es-419"))
        self.assertEqual("values-b+sr+Latn", folder_for("sr-Latn"))

    def test_legacy_language_codes(self):
        self.assertEqual("values-iw", folder_for("he"))
        self.assertEqual("values-in", folder_for("id"))


if __name__ == "__main__":
    unittest.main()
