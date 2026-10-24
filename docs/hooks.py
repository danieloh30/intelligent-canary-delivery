"""Reuse the exact SPA Mermaid renderer; keep authored assets in one location."""
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[1]
SPA = ROOT / 'src/main/resources/META-INF/resources'

def on_post_build(config, **kwargs):
    site = Path(config['site_dir'])
    for source, target in [
        ('vendor/mermaid-12.1.0.min.js', 'assets/vendor/mermaid-12.1.0.min.js'),
        ('mermaid-solid-fills.js', 'javascripts/mermaid-solid-fills.js'),
    ]:
        destination = site / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SPA / source, destination)
    # The docs contain their own Markdown diagram sources, so render every page.
    renderer = (SPA / 'diagrams.js').read_text().replace(
        '    if (!document.querySelector("#architecture.active")) return;\n', '')
    destination = site / 'javascripts/diagrams.js'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(renderer)
