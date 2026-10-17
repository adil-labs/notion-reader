"""Pure identities shared by review rendering and controlled offline evaluation."""


def exposure_identity(case):
    canonical = lambda page: page.strip().lower().replace('-', '')
    content = case['content']
    if case['kind'] == 'topic':
        pages = content.get('memberPageIds')
        if pages is None:
            pages = [member['pageId'] for member in content['members']]
            if content.get('availableCount') != len({canonical(page) for page in pages}):
                return None
        return 'topic', tuple(sorted({canonical(page) for page in pages}))
    return 'pair', tuple(sorted((canonical(content['left']['pageId']), canonical(content['right']['pageId']))))
