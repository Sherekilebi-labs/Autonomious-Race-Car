#!/usr/bin/env python3
"""Build canonical, hashed stopped-only artifact sets; never include private configuration."""
import argparse,hashlib,json,pathlib,shutil
from planner import canonical,strict
KINDS={'geometry':'geometry','policy':'policy','dynamics':'vehicle-model','reference':'reference-trajectory','settings':'app-settings','manifest':'model-manifest'}
def package(entries,out,revision):
    import jsonschema
    root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True);files=[];names=set();kinds=set()
    for kind,path in entries:
        path=pathlib.Path(path)
        if kind not in {*KINDS,'model'} or path.name in names or kind in kinds:raise ValueError('Unsupported/duplicate artifact')
        names.add(path.name);kinds.add(kind)
        if kind=='model':raw=path.read_bytes()
        else:
            value=strict(path);jsonschema.validate(value,strict(root/'maxbot/config/schemas'/f'{KINDS[kind]}.v1.schema.json'));raw=canonical(value)
        if len(raw)>32*1024*1024:raise ValueError('Artifact exceeds 32 MiB')
        (out/path.name).write_bytes(raw);files.append({'kind':kind,'name':path.name,'size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    if not 1<=len(files)<=8 or sum(f['size'] for f in files)>40*1024*1024:raise ValueError('Set size bound')
    manifest={'schemaVersion':1,'revision':revision,'files':files};(out/'set.json').write_bytes(canonical(manifest));return manifest

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--revision',required=True,type=int);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('files',nargs='+',help='kind:path; model.tflite requires adjacent model.tflite.json manifest');a=p.parse_args()
    if a.revision<1:p.error('revision must be positive')
    manifest=package([v.split(':',1) for v in a.files],a.output,a.revision);print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
