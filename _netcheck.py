import urllib.request, sys
urls = sys.argv[1:]
for u in urls:
    try:
        req = urllib.request.Request(u, method='HEAD')
        r = urllib.request.urlopen(req, timeout=25)
        print(r.status, r.headers.get('Content-Length'), u)
    except Exception as e:
        print('FAIL', type(e).__name__, e, u)
