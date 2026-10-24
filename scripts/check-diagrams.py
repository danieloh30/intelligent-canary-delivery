#!/usr/bin/env python3
"""Enforce the workshop palette and shared sources across docs and the SPA."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
sources=root/'src/main/resources/META-INF/resources/diagrams'
for document in [root/'README.md',*(root/'docs').rglob('*.md')]:
    for block in re.findall(r'```mermaid\n(.*?)```',document.read_text(),re.S):
        assert "'look':'handDrawn'" in block,document
        assert "'theme':'base'" in block,document
        assert "'background':'#FFFCF5'" in block,document
        assert 'stroke-width:2px' in block,document
        assert block in [p.read_text() for p in sources.glob('*.mmd')],document
assert 'Expand diagram' in (root/'src/main/resources/META-INF/resources/diagrams.js').read_text()
assert 'workshop-solid-fill' in (root/'src/main/resources/META-INF/resources/mermaid-solid-fills.js').read_text()
print('All Mermaid sources use the reference style and match the SPA diagrams.')
