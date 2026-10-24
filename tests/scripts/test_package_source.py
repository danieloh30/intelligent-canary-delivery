import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('package_source', ROOT/'scripts/package-source.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class PackageSourceTest(unittest.TestCase):
    def test_archive_is_allowlisted_and_has_no_host_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'src').mkdir();(root/'src'/'App.java').write_text('class App {}')
            (root/'Dockerfile').write_text('FROM example');(root/'pom.xml').write_text('<project/>')
            (root/'.env').write_text('OPENAI_API_KEY=not-for-the-build')
            destination=root/'context.tar.gz';module.package(destination,root)
            with tarfile.open(destination,'r:gz') as archive:
                members=archive.getmembers();names={x.name for x in members}
                self.assertEqual({'Dockerfile','pom.xml','src','src/App.java'},names)
                for item in members:
                    self.assertEqual(0,item.uid);self.assertEqual(0,item.gid)
                    self.assertFalse(item.pax_headers)
    def test_archive_rejects_links_outside_allowlist(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'src').mkdir();(root/'Dockerfile').write_text('FROM example');(root/'pom.xml').write_text('<project/>')
            (root/'private-key').write_text('not-for-the-build');(root/'src'/'link').symlink_to(root/'private-key')
            with self.assertRaises(ValueError):module.package(root/'context.tar.gz',root)

if __name__=='__main__':unittest.main()
