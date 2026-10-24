#!/usr/bin/env python3
"""Create an allowlisted, portable OpenShift binary-build archive."""
import argparse
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]

def package(destination, root=ROOT):
    def normalize(info):
        if info.issym() or info.islnk():
            raise ValueError(f'Symlinks are not allowed in the build context: {info.name}')
        info.uid = info.gid = 0
        info.uname = info.gname = ''
        return info
    # BSD tar on macOS can emit extended-attribute headers rejected by the
    # OpenShift source extractor. Python GNU format avoids host metadata.
    with tarfile.open(destination, 'w:gz', format=tarfile.GNU_FORMAT) as archive:
        for name in ('Dockerfile', 'pom.xml', 'src'):
            archive.add(root / name, arcname=name, filter=normalize)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    package(parser.parse_args().destination)
