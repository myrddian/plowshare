#!/usr/bin/env python3
"""Guard public SDK boundaries without requiring native toolchains for server builds."""
import ast
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
errors = []
client = ROOT / 'sdk/python/src/plowshare/client.py'
for cls in ast.parse(client.read_text()).body:
    if not isinstance(cls, ast.ClassDef) or cls.name.startswith('_'):
        continue
    for method in cls.body:
        if not isinstance(method, (ast.FunctionDef, ast.AsyncFunctionDef)) or method.name.startswith('_'):
            continue
        annotations = [arg.annotation for arg in [*method.args.posonlyargs, *method.args.args, *method.args.kwonlyargs]] + [method.returns]
        for annotation in filter(None, annotations):
            names = {node.id for node in ast.walk(annotation) if isinstance(node, ast.Name)}
            if names & {'Any', 'object', 'dict'}:
                errors.append(f'Python public boundary {cls.name}.{method.name} exposes a raw value')

# Go's owning serialization methods are generated and checked by their generator.
# These guards cover the handwritten Client methods and reply facade, where a
# generic Request(any) would restore the old escape hatch despite compilation.
go = (ROOT / 'sdk/go/client.go').read_text()
for signature in re.findall(r'func\s+\([^)]*\*Client\)\s+[A-Z]\w*[^\{]+', go):
    if re.search(r'\bany\b|json\.RawMessage|map\[string\]', signature):
        errors.append('Go public Client boundary exposes a raw value')
cs = (ROOT / 'sdk/dotnet/Plowshare.Sdk/Client.cs').read_text()
for signature in re.findall(r'public\s+(?:static\s+|async\s+|sealed\s+)*[^;{=>]+', cs):
    if re.search(r'JsonElement|JsonNode|\bobject\??\s+\w+|Dictionary<string,\s*object', signature):
        errors.append('C# public Client/reply boundary exposes a raw value')
if errors:
    raise SystemExit('\n'.join(errors))
print('Native SDK public boundary guards passed')
