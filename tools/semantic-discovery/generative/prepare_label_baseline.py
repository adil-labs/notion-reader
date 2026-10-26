"""Prepare an offline label-only comparison without changing baseline groups."""
def prepare(corpus, baseline):
    cases={c['id']:c for c in corpus['cases']}
    result=[]
    for output in baseline['outputs']:
        source={p['id']:p for p in cases[output['case_id']]['passages']}
        for index,topic in enumerate(output['topics']):
            ids=topic['passage_ids']
            if not ids: raise ValueError('empty membership')
            if len(ids)!=len(set(ids)): raise ValueError('duplicate membership')
            if any(pid not in source for pid in ids): raise ValueError('unknown source')
            if any(not source[pid]['eligible'] for pid in ids): raise ValueError('ineligible source')
            result.append({'id':f"{output['case_id']}-label-{index}",
                'baseline_case_id':output['case_id'],'baseline_topic_id':topic['id'],
                'passages':[{'id':pid,'body':source[pid]['body'],'eligible':True} for pid in ids]})
    return {'cases':result}


if __name__ == '__main__':
    import argparse
    import json
    from pathlib import Path
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases',type=Path,required=True)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--split',choices=('development','heldout'),required=True)
    args=parser.parse_args()
    corpus=json.loads(args.cases.read_text(encoding='utf-8'))
    baseline=json.loads(args.baseline.read_text(encoding='utf-8'))
    selected={c['id'] for c in corpus['cases'] if c['split']==args.split}
    baseline['outputs']=[o for o in baseline['outputs'] if o['case_id'] in selected]
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(prepare(corpus,baseline),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
