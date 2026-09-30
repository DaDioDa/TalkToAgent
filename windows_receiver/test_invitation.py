import json
from pathlib import Path
import unittest


VECTORS = Path(__file__).resolve().parents[1] / 'docs' / 'authorization-vectors.json'


class InvitationTests(unittest.TestCase):
    def test_terminal_refuses_redirected_and_narrow_output_without_disclosing_invite(self):
        import io
        import os
        from unittest.mock import patch
        from invitation import parse_uri, show_terminal
        values = parse_uri(json.loads(VECTORS.read_text(encoding='utf-8'))['valid'][0]['uri'])
        redirected = io.StringIO()
        with self.assertRaises(OSError):
            show_terminal(values, out=redirected)
        self.assertEqual(redirected.getvalue(), '')
        class Terminal(io.StringIO):
            def isatty(self): return True
            def fileno(self): return 1
        terminal = Terminal()
        with patch('invitation.os.get_terminal_size', return_value=os.terminal_size((40, 200))):
            with self.assertRaises(OSError):
                show_terminal(values, out=terminal)
        self.assertEqual(terminal.getvalue(), '')

    def test_large_interactive_terminal_renders_qr_without_manual_uri_fallback(self):
        import io
        import os
        from unittest.mock import patch
        from invitation import parse_uri, show_terminal
        values = parse_uri(json.loads(VECTORS.read_text(encoding='utf-8'))['valid'][0]['uri'])
        class Terminal(io.StringIO):
            def isatty(self): return True
            def fileno(self): return 1
        terminal = Terminal()
        with patch('invitation.os.get_terminal_size', return_value=os.terminal_size((300, 200))):
            show_terminal(values, terminal)
        self.assertTrue(terminal.getvalue())
        self.assertNotIn('talktoagent://', terminal.getvalue())
        self.assertNotIn(values['k'], terminal.getvalue())

    def test_shared_der_signature_and_hmac_fixtures(self):
        import hmac
        from authorization import pack, public_key, unb64, b64
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        fixture = json.loads(VECTORS.read_text(encoding='utf-8'))['crypto']
        transcript = fixture['transcript']
        challenge = pack('receiver-challenge', *transcript)
        self.assertEqual(challenge.hex(), fixture['challengePackedHex'])
        public_key(fixture['receiverKey']).verify(unb64(fixture['challengeSignature']),
            challenge, ec.ECDSA(hashes.SHA256()))
        public_key(fixture['phoneKey']).verify(unb64(fixture['phoneSignature']),
            pack('phone-proof', *transcript), ec.ECDSA(hashes.SHA256()))
        public_key(fixture['receiverKey']).verify(unb64(fixture['authorizedSignature']),
            pack('receiver-authorized', *transcript, transcript[-1], fixture['channels'], transcript[3]),
            ec.ECDSA(hashes.SHA256()))
        self.assertEqual(b64(hmac.digest(unb64(fixture['secret']),
            pack('invite-proof', *transcript), 'sha256')), fixture['inviteProof'])

    def test_shared_uri_fixtures(self):
        from invitation import parse_uri, to_uri, target_for
        vectors = json.loads(VECTORS.read_text(encoding='utf-8'))
        for case in vectors['valid']:
            with self.subTest(uri=case['uri']):
                values = parse_uri(case['uri'])
                self.assertEqual(values['c'], case['channel'])
                self.assertEqual(target_for(values), case['target'])
                self.assertEqual(parse_uri(to_uri(values)), values)
        for uri in vectors['invalid']:
            with self.subTest(uri=uri):
                with self.assertRaises(ValueError):
                    parse_uri(uri)


if __name__ == '__main__':
    unittest.main()
