import os
import unittest
from urllib.parse import urlparse
from shura_core.security.network import SafeHTTP

@unittest.skipUnless(os.getenv("SHURA_REAL_NETWORK") == "1", "set SHURA_REAL_NETWORK=1 to opt into live network")
class LiveNetworkTests(unittest.TestCase):
    def test_configured_live_url(self):
        url=os.getenv("SHURA_LIVE_TEST_URL","")
        self.assertTrue(url,"SHURA_LIVE_TEST_URL must be set when SHURA_REAL_NETWORK=1")
        host=urlparse(url).hostname
        body,_,_=SafeHTTP({host},timeout=10,max_bytes=1_000_000).get(url)
        self.assertGreater(len(body),0)

if __name__=="__main__":unittest.main()
