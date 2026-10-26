"""Validate label-only native output against frozen group memberships."""
import json

def validate(inputs, report):
    expected={c['id']:c for c in inputs['cases']}
    if report['status']!='completed': raise ValueError('incomplete run')
    result={}
    for row in report['runs']:
        cid=row['case_id']
        if cid in result: raise ValueError('duplicate group')
        if cid not in expected: raise ValueError('unknown coverage')
        raw=row['rawOutput']
        if len(raw.encode('utf-8'))>32768: raise ValueError('oversized output')
        output=json.loads(raw)
        if set(output)!={'topics'} or len(output['topics'])!=1:
            raise ValueError('expected a single topic')
        topic=output['topics'][0]
        if set(topic)!={'label','passage_ids'}: raise ValueError('schema')
        if not isinstance(topic['label'],str) or not topic['label'].strip() or len(topic['label'])>96:
            raise ValueError('invalid label')
        topic['label'].encode('utf-8')
        ids=topic['passage_ids']
        wanted={p['id'] for p in expected[cid]['passages']}
        if not isinstance(ids,list) or len(ids)!=len(set(ids)) or set(ids)!=wanted:
            raise ValueError('changed membership')
        result[cid]=topic
    if result.keys()!=expected.keys(): raise ValueError('incomplete coverage')
    return result


if __name__=='__main__':
    import argparse
    from pathlib import Path
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    result=validate(json.loads(args.inputs.read_text(encoding='utf-8')),
                    json.loads(args.report.read_text(encoding='utf-8')))
    print(f'Validated {len(result)} unchanged single-label groups')
