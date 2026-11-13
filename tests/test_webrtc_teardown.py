import asyncio
import io
import os
import sys
import threading
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import whisker.webrtc_manager as wm


class FakeClosedTransport:
    def __init__(self, closing=True, sock=None, loop=None):
        self._closing = closing
        self._sock = sock
        self._loop = loop

    def is_closing(self):
        return self._closing

    def sendto(self, data, addr):
        if self._sock is None:
            try:
                self._sock.sendto(data, addr)
            except Exception as exc:
                self._loop.call_exception_handler({"exception": exc})


class InvalidStateError(Exception):
    pass


class FakeRTCPeerConnection:
    def __init__(self, configuration=None):
        self._handlers = {}
        self.remoteDescription = None
        self.localDescription = None
        self.iceConnectionState = "checking"
        self.connectionState = "connecting"
        self.closed = False
        self._connect_task = None

    def on(self, event_name):
        def decorator(fn):
            self._handlers.setdefault(event_name, []).append(fn)
            return fn
        return decorator

    def addTrack(self, track):
        pass

    async def setRemoteDescription(self, desc):
        self.remoteDescription = desc
        # Yield briefly so a concurrent hangup can race mid-negotiation in test
        await asyncio.sleep(0.02)

    async def createAnswer(self):
        return types.SimpleNamespace(sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 0\r\n", type="answer")

    async def setLocalDescription(self, desc):
        self.localDescription = desc
        # Simulate aiortc's internal unawaited RTCPeerConnection.__connect() task
        loop = asyncio.get_running_loop()

        async def _fake_connect():
            await asyncio.sleep(0.03)
            if self.closed:
                raise InvalidStateError("RTCIceTransport is closed")

        self._connect_task = loop.create_task(_fake_connect())

        # Simulate aioice's Transaction.__retry() timer firing right after transport closes
        def _fake_retry():
            proto = FakeStunProtocol(FakeClosedTransport(closing=True, sock=None, loop=None))
            proto.send_stun(b"stun", ("1.2.3.4", 1234))

        loop.call_later(0.02, _fake_retry)

    async def addIceCandidate(self, cand):
        pass

    async def close(self):
        self.closed = True
        self.iceConnectionState = "closed"
        self.connectionState = "closed"


class FakeStunProtocol:
    def __init__(self, transport):
        self.transport = transport

    def send_stun(self, message, addr):
        self.transport.sendto(bytes(message), addr)


class TestWebRTCTeardown(unittest.TestCase):
    def test_aioice_safe_send_stun_patch_prevents_attribute_error(self):
        fake_aioice = types.ModuleType("aioice")
        fake_aioice_ice = types.ModuleType("aioice.ice")
        fake_aioice_ice.StunProtocol = FakeStunProtocol
        fake_aioice.ice = fake_aioice_ice

        with patch.dict(sys.modules, {"aioice": fake_aioice, "aioice.ice": fake_aioice_ice}), \
             patch.object(wm, "AIORTC_AVAILABLE", True):
            wm._install_aioice_safe_shutdown_patch()
            proto = FakeStunProtocol(FakeClosedTransport(closing=True, sock=None, loop=None))
            # Must return silently without AttributeError
            proto.send_stun(b"hello", ("127.0.0.1", 5000))

    def test_run_incoming_call_rapid_hangup_no_unretrieved_task_or_timer_errors(self):
        stderr_capture = io.StringIO()
        posts = []

        class DummyGui:
            def __init__(self, *args, **kwargs):
                pass
            def start(self):
                pass
            def close(self):
                pass

        sess = wm.WhiskerWebRTCSession(
            server_url="http://127.0.0.1:9999",
            client_uuid="u1",
            client_token="t1",
            client_name="backup",
            host_uuid="h1",
            host_name="b100",
            call_id="call_test_1"
        )
        sess._post_json = lambda ep, payload: posts.append((ep, payload)) or {"status": "ok"}

        call_loop = asyncio.new_event_loop()
        sess.loop = call_loop
        sess._using_lifecycle_runner = True
        call_loop.set_exception_handler(sess._asyncio_exception_handler)

        def runner():
            asyncio.set_event_loop(call_loop)
            try:
                call_loop.run_until_complete(
                    sess.run_incoming_call(
                        offer_sdp="v=0\r\na=mid:0\r\n",
                        peer_name="b100",
                        is_video=True,
                        call_id="call_test_1"
                    )
                )
            finally:
                if not call_loop.is_closed():
                    call_loop.close()

        fake_aioice = types.ModuleType("aioice")
        fake_aioice_ice = types.ModuleType("aioice.ice")
        fake_aioice_ice.StunProtocol = FakeStunProtocol
        fake_aioice.ice = fake_aioice_ice

        with patch.dict(sys.modules, {"aioice": fake_aioice, "aioice.ice": fake_aioice_ice}), \
             patch.object(wm, "AIORTC_AVAILABLE", True), \
             patch.object(wm, "RTCPeerConnection", FakeRTCPeerConnection), \
             patch.object(wm, "RTCSessionDescription", lambda sdp, type: (sdp, type)), \
             patch.object(wm, "RTCConfiguration", lambda iceServers: iceServers), \
             patch.object(wm, "RTCIceServer", lambda urls: urls), \
             patch.object(wm, "CallGui", DummyGui), \
             patch.object(sys, "stderr", stderr_capture):
            wm._install_aioice_safe_shutdown_patch()

            t = threading.Thread(target=runner, daemon=True)
            t.start()
            # Trigger remote hangup right after accept/connect starts
            threading.Event().wait(0.03)
            sess.hangup(notify_server=False)
            t.join(timeout=3.0)

        self.assertFalse(t.is_alive(), "Incoming call thread did not exit cleanly after hangup")
        self.assertTrue(call_loop.is_closed(), "Call event loop was not closed")
        self.assertTrue(sess._cleanup_done, "Session cleanup did not finish")
        err_text = stderr_capture.getvalue()
        self.assertNotIn("Task exception was never retrieved", err_text)
        self.assertNotIn("AttributeError", err_text)
        self.assertNotIn("InvalidStateError", err_text)

    def test_hangup_before_answer_sent_aborts_signaling(self):
        posts = []

        class DummyGui:
            def __init__(self, *args, **kwargs):
                pass
            def start(self):
                pass
            def close(self):
                pass

        sess = wm.WhiskerWebRTCSession(
            server_url="http://127.0.0.1:9999",
            client_uuid="u1",
            client_token="t1",
            client_name="backup",
            host_uuid="h1",
            host_name="b100",
            call_id="call_test_2"
        )
        sess._post_json = lambda ep, payload: posts.append((ep, payload)) or {"status": "ok"}

        call_loop = asyncio.new_event_loop()
        sess.loop = call_loop
        sess._using_lifecycle_runner = True
        call_loop.set_exception_handler(sess._asyncio_exception_handler)

        def runner():
            asyncio.set_event_loop(call_loop)
            try:
                call_loop.run_until_complete(
                    sess.run_incoming_call(
                        offer_sdp="v=0\r\na=mid:0\r\n",
                        peer_name="b100",
                        is_video=True,
                        call_id="call_test_2"
                    )
                )
            finally:
                if not call_loop.is_closed():
                    call_loop.close()

        with patch.object(wm, "AIORTC_AVAILABLE", True), \
             patch.object(wm, "RTCPeerConnection", FakeRTCPeerConnection), \
             patch.object(wm, "RTCSessionDescription", lambda sdp, type: (sdp, type)), \
             patch.object(wm, "RTCConfiguration", lambda iceServers: iceServers), \
             patch.object(wm, "RTCIceServer", lambda urls: urls), \
             patch.object(wm, "CallGui", DummyGui):
            t = threading.Thread(target=runner, daemon=True)
            t.start()
            # Hangup while setRemoteDescription is awaiting (0.02s)
            threading.Event().wait(0.005)
            sess.hangup(notify_server=False)
            t.join(timeout=3.0)

        self.assertFalse(t.is_alive())
        self.assertTrue(call_loop.is_closed())
        self.assertEqual(posts, [], "Answer must not be sent if call hung up mid-negotiation")


if __name__ == "__main__":
    unittest.main()
