#!/usr/bin/env python3
"""Install measured AndroidX profile outputs; never fabricate profile rules."""
import argparse,pathlib
p=argparse.ArgumentParser();p.add_argument('reports',type=pathlib.Path);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
found=False
for suffix,dest in [('baseline-prof.txt','baseline-prof.txt'),('startup-prof.txt','startup-prof.txt')]:
    inputs=list(args.reports.rglob('*'+suffix))
    lines=sorted({line.strip() for source in inputs for line in source.read_text().splitlines() if line.strip() and not line.startswith('#')})
    if lines:
        (root/'app/src/main'/dest).write_text('\n'.join(lines)+'\n');print(f'Installed {len(lines)} measured rules from {len(inputs)} files into {dest}');found=True
if not found:raise SystemExit('No generated profile output found. Run the Android profile generator first.')
