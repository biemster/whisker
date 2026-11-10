import os
import sys
import time
import json
import math
import struct
import shutil
import subprocess
import asyncio
import threading
import queue
import re
import random
import urllib.request
import urllib.parse
try:
    from PIL import Image, ImageDraw, ImageFont
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False
    Image = None
    ImageDraw = None
    ImageFont = None

try:
    import av
    from aiortc import (
        RTCPeerConnection,
        RTCSessionDescription,
        RTCIceCandidate,
        RTCConfiguration,
        RTCIceServer,
        VideoStreamTrack,
        AudioStreamTrack
    )
    from aiortc.contrib.media import MediaPlayer, MediaRecorder
    AIORTC_AVAILABLE = True
except ImportError:
    av = None
    RTCPeerConnection = object
    RTCSessionDescription = object
    RTCIceCandidate = object
    RTCConfiguration = object
    RTCIceServer = object
    VideoStreamTrack = object
    AudioStreamTrack = object
    MediaPlayer = None
    MediaRecorder = None
    AIORTC_AVAILABLE = False


def sanitize_filename_part(name: str) -> str:
    cleaned = re.sub(r'[^\w\-]', '_', (name or '').strip())
    cleaned = re.sub(r'_+', '_', cleaned).strip('_')
    return cleaned or "Whisker"


def generate_call_recording_filename(caller: str, callee: str, epoch: int = None) -> str:
    c_caller = sanitize_filename_part(caller)
    c_callee = sanitize_filename_part(callee)
    epoch_val = int(epoch if epoch is not None else time.time())
    return f"{c_caller}.{c_callee}.{epoch_val}.mp4"


def is_headless_environment() -> bool:
    if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        return True
    return False


class WhiskerStreamRecorder:
    """
    Records incoming and outgoing WebRTC audio & video streams to a single MP4 container on disk.
    File name convention: <caller>.<callee>.<unixepoch>.mp4
    """
    def __init__(self, filename):
        self.filename = filename
        self.recorder = None
        self.is_recording = False
        self.started = False
        self._tracks = set()

    def add_track(self, track):
        if not track or not AIORTC_AVAILABLE:
            return
        if track in self._tracks:
            return
        self._tracks.add(track)
        if self.recorder is None and MediaRecorder is not None:
            try:
                self.recorder = MediaRecorder(self.filename)
            except Exception as e:
                print(f"[Recorder Init Error] {e}")

        if self.recorder is not None:
            try:
                self.recorder.addTrack(track)
            except Exception as e:
                print(f"[Recorder Track Error] {e}")

    async def start(self):
        if self.recorder and not self.started:
            try:
                self.started = True
                await self.recorder.start()
                self.is_recording = True
                print(f"💾 [WebRTC Recorder] Saving live audio+video stream to disk: '{self.filename}'")
            except Exception as e:
                print(f"[Recorder Start Error] {e}")

    async def stop(self):
        if self.recorder and self.is_recording:
            try:
                self.is_recording = False
                await self.recorder.stop()
                if os.path.exists(self.filename):
                    size_kb = os.path.getsize(self.filename) / 1024.0
                    print(f"💾 [WebRTC Recorder] Stream successfully saved to '{self.filename}' ({size_kb:.1f} KB).")
                else:
                    print(f"💾 [WebRTC Recorder] Stream recording completed for '{self.filename}'.")
            except Exception as e:
                print(f"[Recorder Stop Error] {e}")


class PlaceholderVideoTrack(VideoStreamTrack):
    """
    Generates a continuous, placeholder video stream at 30 FPS.
    Renders an animated live canvas with timestamp, peer info, and pulse radar waves.
    Ensures the CLI always provides a video track to trigger reciprocal camera video from mobile app.
    """
    def __init__(self, label="Whisker CLI Stream", width=640, height=480):
        super().__init__()
        self.label = label
        self.width = width
        self.height = height
        self.frame_count = 0
        self.start_time = time.time()

    async def recv(self):
        pts, time_base = await self.next_timestamp()
        self.frame_count += 1
        elapsed = time.time() - self.start_time

        if PIL_AVAILABLE and Image and ImageDraw:
            # Create base canvas with dark gradient/theme
            img = Image.new("RGB", (self.width, self.height), color=(11, 15, 25))
            draw = ImageDraw.Draw(img)

            # Draw decorative grid & border
            draw.rectangle([10, 10, self.width - 10, self.height - 10], outline=(22, 78, 99), width=2)

            # Header banner
            draw.rectangle([10, 10, self.width - 10, 60], fill=(14, 23, 38))
            draw.text((25, 25), "🐾 WHISKER LIVE STREAM", fill=(6, 182, 212))

            time_str = time.strftime("%H:%M:%S")
            draw.text((self.width - 160, 25), f"TIME: {time_str}", fill=(148, 163, 184))

            # Center animated pulse radar / wave
            cx, cy = self.width // 2, self.height // 2
            for r_offset in [0, 40, 80, 120]:
                r = int((((elapsed * 60) + r_offset) % 160) + 20)
                alpha = max(10, 255 - int(r * 1.4))
                draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(6, 182, 212, alpha), width=2)

            # Central badge
            draw.ellipse([cx - 45, cy - 45, cx + 45, cy + 45], fill=(30, 41, 59), outline=(6, 182, 212), width=2)
            draw.text((cx - 16, cy - 12), "📹", fill=(248, 250, 252))

            # Animated sine wave across the bottom
            wave_points = []
            for x in range(30, self.width - 30, 8):
                y = int(cy + 130 + math.sin((x * 0.05) + (elapsed * 5)) * 25)
                wave_points.append((x, y))
            if len(wave_points) > 1:
                draw.line(wave_points, fill=(16, 185, 129), width=3)

            # Labels
            draw.text((cx - 110, cy + 55), self.label, fill=(248, 250, 252))
            draw.text((cx - 130, cy + 80), "Placeholder Video (No -v source specified)", fill=(148, 163, 184))
            fps_text = f"FPS: 30 | Frame: {self.frame_count} | Audio+Video Active"
            draw.text((30, self.height - 35), fps_text, fill=(100, 116, 139))

            if av:
                frame = av.VideoFrame.from_image(img)
                frame.pts = pts
                frame.time_base = time_base
                return frame

        if av:
            frame = av.VideoFrame(width=self.width, height=self.height, format="yuv420p")
            frame.pts = pts
            frame.time_base = time_base
            return frame
        return None


SyntheticVideoTrack = PlaceholderVideoTrack


class SilenceAudioTrack(AudioStreamTrack):
    """
    Generates 48kHz 16-bit PCM stereo silence audio frames (20ms per packet).
    Ensures the CLI always provides an audio track when no -a source is specified.
    """
    def __init__(self, sample_rate=48000):
        super().__init__()
        self.sample_rate = sample_rate
        self.samples_per_frame = 960  # 20ms per packet
        self.raw_silence = bytes(bytearray(self.samples_per_frame * 4))

    async def recv(self):
        pts, time_base = await self.next_timestamp()
        if not av:
            return None
        frame = av.AudioFrame(format="s16", layout="stereo", samples=self.samples_per_frame)
        frame.sample_rate = self.sample_rate
        frame.planes[0].update(self.raw_silence)
        frame.pts = pts
        frame.time_base = time_base
        return frame


class SyntheticAudioTrack(AudioStreamTrack):
    """
    Generates 48kHz 16-bit PCM stereo audio with subtle pleasant tone.
    """
    def __init__(self, sample_rate=48000, tone_freq=440.0, volume=0.05):
        super().__init__()
        self.sample_rate = sample_rate
        self.tone_freq = tone_freq
        self.volume = volume
        self.samples_per_frame = 960  # 20ms per packet
        self.time_offset = 0.0

    async def recv(self):
        pts, time_base = await self.next_timestamp()
        raw_bytes = bytearray()

        for i in range(self.samples_per_frame):
            t = (self.time_offset + (i / self.sample_rate))
            val = int(math.sin(2.0 * math.pi * self.tone_freq * t) * self.volume * 32767.0) if self.volume > 0 else 0
            raw_bytes.extend(struct.pack("<hh", val, val))

        self.time_offset += (self.samples_per_frame / self.sample_rate)

        if not av:
            return None
        frame = av.AudioFrame(format="s16", layout="stereo", samples=self.samples_per_frame)
        frame.sample_rate = self.sample_rate
        frame.planes[0].update(bytes(raw_bytes))
        frame.pts = pts
        frame.time_base = time_base
        return frame


def create_video_source_track(source_path):
    """
    Creates an aiortc VideoStreamTrack from a Linux source (e.g. /dev/video0, file, or synthetic).
    """
    if not source_path:
        return None

    if not AIORTC_AVAILABLE:
        print("[WebRTC] Warning: aiortc library is not installed.")
        return None

    source_str = str(source_path).strip()
    if source_str.startswith("/dev/"):
        try:
            print(f"[WebRTC Video] Accessing Linux V4L2 device: {source_str}...")
            player = MediaPlayer(
                source_str,
                format="v4l2",
                options={"video_size": "640x480", "framerate": "30", "input_format": "mjpeg"}
            )
            return player.video
        except Exception as e:
            print(f"[WebRTC Video] Note: V4L2 device '{source_str}' could not be directly captured ({e}). Using synthetic video fallback.")
            return SyntheticVideoTrack(label=f"Linux Camera ({source_str})")
    elif os.path.exists(source_str):
        try:
            print(f"[WebRTC Video] Playing media file: {source_str}")
            player = MediaPlayer(source_str)
            return player.video
        except Exception as e:
            print(f"[WebRTC Video] Error opening media file '{source_str}': {e}. Using synthetic video.")
            return SyntheticVideoTrack(label=f"Video ({source_str})")
    else:
        print(f"[WebRTC Video] Custom source specified: '{source_str}'. Streaming synthesized Linux video track.")
        return SyntheticVideoTrack(label=f"Video Source ({source_str})")


def create_audio_source_track(source_path):
    """
    Creates an aiortc AudioStreamTrack from a Linux audio source (e.g. default, pulse, alsa, file, synthetic).
    """
    if not source_path:
        return None

    if not AIORTC_AVAILABLE:
        return None

    source_str = str(source_path).strip()
    if source_str in ["default", "pulse", "/dev/dsp"] or source_str.startswith("hw:"):
        try:
            fmt = "alsa" if (source_str.startswith("hw:") or source_str == "default") else ("pulse" if source_str == "pulse" else None)
            print(f"[WebRTC Audio] Opening Linux audio source '{source_str}' (format={fmt})...")
            player = MediaPlayer(source_str, format=fmt)
            return player.audio
        except Exception as e:
            print(f"[WebRTC Audio] Note: Could not open audio device '{source_str}' ({e}). Using synthetic audio stream.")
            return SyntheticAudioTrack(tone_freq=440.0, volume=0.03)
    elif os.path.exists(source_str):
        try:
            print(f"[WebRTC Audio] Playing audio file: {source_str}")
            player = MediaPlayer(source_str)
            return player.audio
        except Exception as e:
            print(f"[WebRTC Audio] Error opening audio file '{source_str}': {e}. Using synthetic audio.")
            return SyntheticAudioTrack()
    else:
        print(f"[WebRTC Audio] Custom audio source: '{source_str}'. Streaming generated audio.")
        return SyntheticAudioTrack(tone_freq=440.0, volume=0.03)


class CallGui(threading.Thread):
    """
    Tkinter-based Video/Audio call display window.
    Strictly read-only with NO user input or action buttons.
    Call hangup and muting are managed entirely by the mobile app.
    """
    def __init__(self, peer_name="Mobile Contact", is_video=True, on_close=None):
        super().__init__(daemon=True)
        self.peer_name = peer_name
        self.is_video = is_video
        self.on_close = on_close
        self.frame_queue = queue.Queue(maxsize=10)
        self.running = True
        self.has_display = False
        self.start_time = time.time()
        self.root = None
        self.canvas = None
        self.status_lbl = None
        self.timer_lbl = None
        self.audio_viz_lbl = None
        self.photo_img = None
        self.wave_counter = 0

    def run(self):
        try:
            import tkinter as tk
            from PIL import ImageTk

            self.root = tk.Tk()
            self.has_display = True
            self.root.title(f"Whisker Call • {self.peer_name}")
            self.root.geometry("480x720")
            self.root.minsize(360, 480)
            self.root.configure(bg="#0b0f19")
            self.root.resizable(True, True)

            # Handle user closing the window
            def handle_window_close():
                if self.on_close:
                    try:
                        self.on_close()
                    except Exception:
                        pass
                self.close()

            self.root.protocol("WM_DELETE_WINDOW", handle_window_close)

            # Header frame
            header = tk.Frame(self.root, bg="#0e1726", height=50)
            header.pack(fill="x", side="top")

            live_dot = tk.Label(header, text="●", fg="#10b981", bg="#0e1726", font=("Helvetica", 14, "bold"))
            live_dot.pack(side="left", padx=(16, 6), pady=12)

            title_text = f"LIVE CALL: {self.peer_name}"
            self.status_lbl = tk.Label(header, text=title_text, fg="#f8fafc", bg="#0e1726", font=("Helvetica", 11, "bold"))
            self.status_lbl.pack(side="left", pady=12)

            self.timer_lbl = tk.Label(header, text="00:00", fg="#06b6d4", bg="#0e1726", font=("Helvetica", 11, "bold"))
            self.timer_lbl.pack(side="right", padx=16, pady=12)

            # Video / Media Canvas
            self.canvas = tk.Canvas(self.root, width=480, height=630, bg="#030712", highlightthickness=0)
            self.canvas.pack(fill="both", expand=True)

            # Draw initial placeholder
            self._draw_placeholder()

            # Footer frame (Explaining mobile remote management, strictly NO buttons)
            footer = tk.Frame(self.root, bg="#0e1726", height=40)
            footer.pack(fill="x", side="bottom")

            footer_lbl = tk.Label(
                footer,
                text="📱 Call controls, muting, and hangup are remotely managed by the Mobile App",
                fg="#64748b",
                bg="#0e1726",
                font=("Helvetica", 9, "italic")
            )
            footer_lbl.pack(pady=10)

            # Schedule queue polling loop
            self.root.after(33, self._gui_update_loop)
            self.root.mainloop()

        except Exception as e:
            self.has_display = False
            print(f"[Whisker Call GUI] Display note: Graphical window could not be initialized ({e}). Running in stream mode.")

    def _draw_placeholder(self):
        if not self.canvas:
            return
        cw = max(self.canvas.winfo_width(), 480)
        ch = max(self.canvas.winfo_height(), 630)
        cx, cy = cw // 2, ch // 2
        self.canvas.delete("all")
        self.canvas.create_rectangle(0, 0, cw, ch, fill="#030712")
        self.canvas.create_oval(cx - 50, cy - 80, cx + 50, cy + 20, fill="#1e293b", outline="#06b6d4", width=2)
        self.canvas.create_text(cx, cy - 30, text="📹" if self.is_video else "📞", font=("Helvetica", 36))
        self.canvas.create_text(cx, cy + 50, text=f"Connected with {self.peer_name}", fill="#f8fafc", font=("Helvetica", 14, "bold"))
        self.canvas.create_text(cx, cy + 85, text="WebRTC P2P Media Stream Connected", fill="#06b6d4", font=("Helvetica", 10))

    def _gui_update_loop(self):
        if not self.running or not self.root:
            if self.root:
                try:
                    self.root.destroy()
                except Exception:
                    pass
            return

        # Update duration timer
        elapsed = int(time.time() - self.start_time)
        mins = elapsed // 60
        secs = elapsed % 60
        if self.timer_lbl:
            self.timer_lbl.config(text=f"{mins:02d}:{secs:02d}")

        # Check for new incoming video frames
        from PIL import ImageTk
        latest_img = None
        while not self.frame_queue.empty():
            try:
                latest_img = self.frame_queue.get_nowait()
            except queue.Empty:
                break

        if latest_img and self.canvas:
            try:
                cw = self.canvas.winfo_width()
                ch = self.canvas.winfo_height()
                if cw < 50 or ch < 50:
                    cw = 480
                    ch = 630

                # Calculate aspect-ratio-preserving dimensions (fit within canvas, never stretch/squash)
                img_w, img_h = latest_img.size
                scale = min(cw / img_w, ch / img_h)
                new_w = max(1, int(img_w * scale))
                new_h = max(1, int(img_h * scale))

                resized = latest_img.resize((new_w, new_h), Image.Resampling.BILINEAR)
                self.photo_img = ImageTk.PhotoImage(resized)

                offset_x = (cw - new_w) // 2
                offset_y = (ch - new_h) // 2

                self.canvas.delete("all")
                self.canvas.create_image(offset_x, offset_y, image=self.photo_img, anchor="nw")
            except Exception as e:
                pass
        elif not self.is_video and self.canvas:
            # Render animated audio waveform
            cw = max(self.canvas.winfo_width(), 480)
            ch = max(self.canvas.winfo_height(), 630)
            cx, cy = cw // 2, (ch // 2) + 120
            self.wave_counter += 1
            self.canvas.delete("wave")
            for i in range(-10, 11):
                x = cx + (i * 18)
                h = int(abs(math.sin((self.wave_counter * 0.15) + (i * 0.4))) * 35) + 6
                self.canvas.create_line(x, cy - h, x, cy + h, fill="#06b6d4", width=4, tags="wave")

        self.root.after(33, self._gui_update_loop)

    def update_frame(self, pil_image):
        if not self.running:
            return
        try:
            if self.frame_queue.full():
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
            self.frame_queue.put_nowait(pil_image)
        except Exception:
            pass

    def close(self):
        self.running = False
        if self.root:
            try:
                self.root.after(0, self.root.destroy)
            except Exception:
                pass


class WhiskerWebRTCSession:
    """
    Unified WebRTC Calling Engine for Outgoing & Incoming Calls.
    A call is always both audio and video.
    If headless or if save_streams is set, audio+video is written to disk as <caller>.<callee>.<unixepoch>.mp4.
    """
    def __init__(self, server_url, client_uuid, client_token, client_name="Whisker CLI", host_uuid=None, host_name="Host", call_id=None, save_streams=False, on_hangup=None):
        self.server_url = server_url.rstrip("/")
        self.client_uuid = client_uuid
        self.client_token = client_token
        self.client_name = client_name
        self.host_uuid = host_uuid or ""
        self.host_name = host_name or "Host"
        self.call_id = call_id or f"call_{int(time.time()*1000)}_{random.randint(1000,9999)}"
        self.save_streams = save_streams
        self.on_hangup = on_hangup
        self.is_headless = is_headless_environment()
        self.should_record = self.save_streams or self.is_headless
        self.recorder = None
        self.recording_filename = None
        self.pc = None
        self.gui = None
        self.is_active = False
        self.loop = None
        self.tasks = []
        self.pending_candidates = []
        self.audio_proc = None
        self.audio_resampler = None
        self._remote_tracks_count = 0
        self._ended_remote_tracks_count = 0
        self._disconnected_check_task = None

    def _start_audio_player(self):
        if self.audio_proc:
            return
        player_cmd = None
        for cmd in ['ffplay', 'aplay', 'paplay']:
            if shutil.which(cmd):
                player_cmd = cmd
                break
        if not player_cmd:
            return
        try:
            if player_cmd == 'ffplay':
                args = ['ffplay', '-nodisp', '-autoexit', '-loglevel', 'quiet', '-f', 's16le', '-ar', '48000', '-ac', '2', '-i', 'pipe:0']
            elif player_cmd == 'aplay':
                args = ['aplay', '-q', '-f', 'S16_LE', '-r', '48000', '-c', '2']
            elif player_cmd == 'paplay':
                args = ['paplay', '--raw', '--format=s16le', '--rate=48000', '--channels=2']
            else:
                args = None

            if args:
                self.audio_proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if av:
                    try:
                        self.audio_resampler = av.AudioResampler(format="s16", layout="stereo", rate=48000)
                    except Exception:
                        self.audio_resampler = None
                print(f"🔊 [WebRTC Audio] Speaker playback initialized via '{player_cmd}'.")
        except Exception as e:
            self.audio_proc = None
            self.audio_resampler = None

    def _post_json(self, endpoint, payload):
        req_data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.server_url}{endpoint}",
            data=req_data,
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            print(f"[WebRTC Signaling Error] {endpoint}: {e}")
            return None

    def send_ice_candidate(self, candidate):
        if not candidate:
            return
        candidate_dict = {
            "candidate": candidate.candidate,
            "sdpMid": candidate.sdpMid,
            "sdpMLineIndex": candidate.sdpMLineIndex
        }
        payload = {
            "action": "signal",
            "event": "signal",
            "type": "webrtc_ice_candidate",
            "uuid": self.client_uuid,
            "token": self.client_token,
            "host_uuid": self.host_uuid,
            "call_id": self.call_id,
            "candidate": candidate_dict
        }
        self._post_json("/api/host", payload)

    async def _check_disconnected_timeout(self):
        try:
            await asyncio.sleep(2.0)
            if self.is_active and self.pc:
                conn_st = getattr(self.pc, "connectionState", "unknown")
                ice_st = getattr(self.pc, "iceConnectionState", "unknown")
                if conn_st in ["disconnected", "failed", "closed"] or ice_st in ["disconnected", "failed", "closed"]:
                    print(f"📴 [WebRTC Peer Disconnected] Remote connection closed.")
                    self.hangup(notify_server=False)
        except Exception:
            pass
        finally:
            self._disconnected_check_task = None

    async def _handle_remote_track(self, track):
        print(f"[WebRTC Media] Remote track connected: {track.kind.upper()}")
        self._remote_tracks_count += 1
        if track.kind == "audio":
            self._start_audio_player()
        frame_counter = 0
        try:
            while self.is_active:
                frame = await track.recv()
                frame_counter += 1
                if track.kind == "video":
                    # Convert to PIL image and send to GUI
                    if hasattr(frame, 'to_image'):
                        pil_img = frame.to_image()
                        if self.gui:
                            self.gui.update_frame(pil_img)
                    if frame_counter % 90 == 0:
                        print(f"📹 [WebRTC Video] Frame {frame_counter} received ({frame.width}x{frame.height})")
                elif track.kind == "audio":
                    if self.audio_proc and self.audio_proc.stdin:
                        try:
                            if self.audio_resampler:
                                resampled = self.audio_resampler.resample(frame)
                                for rf in resampled:
                                    if hasattr(rf, 'planes') and len(rf.planes) > 0:
                                        self.audio_proc.stdin.write(rf.planes[0].to_bytes())
                            elif hasattr(frame, 'planes') and len(frame.planes) > 0:
                                self.audio_proc.stdin.write(frame.planes[0].to_bytes())
                            self.audio_proc.stdin.flush()
                        except Exception:
                            pass
                    if frame_counter % 150 == 0:
                        print(f"🎙️ [WebRTC Audio] Audio packets active (Samples: {frame.samples})")
        except Exception:
            pass
        finally:
            print(f"[WebRTC Media] Remote track '{track.kind}' ended.")
            self._ended_remote_tracks_count += 1
            if self.is_active and self._remote_tracks_count > 0 and self._ended_remote_tracks_count >= self._remote_tracks_count:
                self.hangup(notify_server=False)

    def _setup_peer_connection(self, video_source=None, audio_source=None, is_caller=True):
        config = RTCConfiguration(
            iceServers=[
                RTCIceServer(urls=["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"])
            ]
        )
        self.pc = RTCPeerConnection(configuration=config)

        # For the CLI client, a call is ALWAYS both audio and video.
        # Video Track:
        if video_source:
            v_track = create_video_source_track(video_source)
        else:
            label = f"{self.client_name} -> {self.host_name}" if is_caller else f"{self.client_name} (from {self.host_name})"
            v_track = PlaceholderVideoTrack(label=label)

        if v_track:
            self.pc.addTrack(v_track)
            desc = f"device/file ({video_source})" if video_source else "placeholder image stream"
            print(f"[WebRTC Video Track] Added {desc}.")

        # Audio Track:
        if audio_source:
            a_track = create_audio_source_track(audio_source)
        else:
            a_track = SilenceAudioTrack()

        if a_track:
            self.pc.addTrack(a_track)
            desc = f"device/file ({audio_source})" if audio_source else "silence audio stream"
            print(f"[WebRTC Audio Track] Added {desc}.")

        @self.pc.on("icecandidate")
        def on_icecandidate(candidate):
            if candidate:
                self.send_ice_candidate(candidate)

        @self.pc.on("track")
        def on_track(track):
            if self.should_record and self.recorder:
                self.recorder.add_track(track)
                asyncio.create_task(self.recorder.start())
            task = asyncio.create_task(self._handle_remote_track(track))
            self.tasks.append(task)

        @self.pc.on("iceconnectionstatechange")
        def on_iceconnectionstatechange():
            if self.pc:
                state = getattr(self.pc, "iceConnectionState", "unknown")
                print(f"⚡ [WebRTC ICE] ICE Connection state: {state.upper()}")
                if state in ["failed", "closed"]:
                    self.hangup(notify_server=False)
                elif state == "disconnected":
                    if not self._disconnected_check_task and self.loop and self.loop.is_running():
                        self._disconnected_check_task = asyncio.create_task(self._check_disconnected_timeout())

        @self.pc.on("connectionstatechange")
        def on_connectionstatechange():
            if self.pc:
                state = getattr(self.pc, "connectionState", "unknown")
                print(f"⚡ [WebRTC State] Connection state: {state.upper()}")
                if state in ["failed", "closed"]:
                    self.hangup(notify_server=False)
                elif state == "disconnected":
                    if not self._disconnected_check_task and self.loop and self.loop.is_running():
                        self._disconnected_check_task = asyncio.create_task(self._check_disconnected_timeout())

    async def start_outgoing_call(self, video_source=None, audio_source=None):
        """
        Initiates outgoing WebRTC call with SDP offer.
        A call is always both audio and video.
        """
        if not AIORTC_AVAILABLE:
            print("Error: aiortc is required for WebRTC calls. Please install with 'pip install aiortc pillow av'.")
            return False

        self.is_active = True
        # Filename convention: <caller>.<callee>.<unixepoch>.mp4
        self.recording_filename = generate_call_recording_filename(self.client_name, self.host_name)
        if self.should_record:
            self.recorder = WhiskerStreamRecorder(self.recording_filename)
            mode_desc = "Headless environment" if self.is_headless else "--save-streams parameter"
            print(f"💾 [Stream Recording] Target file: '{self.recording_filename}' (Reason: {mode_desc})")

        self._setup_peer_connection(video_source=video_source, audio_source=audio_source, is_caller=True)

        # Start Tkinter GUI window (handles headless environments gracefully)
        self.gui = CallGui(peer_name=self.host_name, is_video=True, on_close=lambda: self.hangup(notify_server=True))
        self.gui.start()

        # Create SDP offer
        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)

        # Send call offer to server - video is always True for full A/V negotiation
        payload = {
            "action": "call",
            "event": "call",
            "uuid": self.client_uuid,
            "token": self.client_token,
            "name": self.client_name,
            "call_id": self.call_id,
            "video": True,
            "host_uuid": self.host_uuid,
            "sdp": self.pc.localDescription.sdp
        }
        print(f"📞 [WebRTC Calling] Initiating Audio+Video call to '{self.host_name}' (Call ID: {self.call_id})...")
        res = self._post_json("/api/host", payload)
        if res and res.get("error"):
            print(f"❌ Call error: {res['error']}")
            self.hangup(notify_server=False)
            return False
        if res and res.get("call_id"):
            self.call_id = res["call_id"]

        return True

    async def accept_incoming_call(self, offer_sdp, peer_name="Mobile Host", is_video=True, video_source=None, audio_source=None, call_id=None):
        """
        Answers an incoming WebRTC call from mobile app with SDP answer.
        A call is always answered with both audio and video.
        """
        if not AIORTC_AVAILABLE:
            print("Error: aiortc is required for WebRTC calls.")
            return False

        self.is_active = True
        self.host_name = peer_name
        if call_id:
            self.call_id = call_id

        # Filename convention for incoming call: <caller>.<callee>.<unixepoch>.mp4
        self.recording_filename = generate_call_recording_filename(self.host_name, self.client_name)
        if self.should_record:
            self.recorder = WhiskerStreamRecorder(self.recording_filename)
            mode_desc = "Headless environment" if self.is_headless else "--save-streams parameter"
            print(f"💾 [Stream Recording] Target file: '{self.recording_filename}' (Reason: {mode_desc})")

        self._setup_peer_connection(video_source=video_source, audio_source=audio_source, is_caller=False)

        # Start Tkinter GUI window
        self.gui = CallGui(peer_name=self.host_name, is_video=True, on_close=lambda: self.hangup(notify_server=True))
        self.gui.start()

        # Set remote offer SDP
        remote_desc = RTCSessionDescription(sdp=offer_sdp, type="offer")
        await self.pc.setRemoteDescription(remote_desc)

        # Flush any queued candidates
        while self.pending_candidates:
            c = self.pending_candidates.pop(0)
            try:
                await self.pc.addIceCandidate(c)
            except Exception:
                pass

        # Create answer SDP
        answer = await self.pc.createAnswer()
        await self.pc.setLocalDescription(answer)

        # Send answer to server via /api/host
        payload = {
            "action": "signal",
            "event": "signal",
            "type": "webrtc_call_answer",
            "uuid": self.client_uuid,
            "token": self.client_token,
            "host_uuid": self.host_uuid,
            "call_id": self.call_id,
            "sdp": self.pc.localDescription.sdp
        }
        print(f"📞 [WebRTC Answer] Answering Audio+Video call from '{self.host_name}' (Call ID: {self.call_id})...")
        self._post_json("/api/host", payload)
        return True

    async def handle_remote_answer(self, answer_sdp):
        if not self.pc or not self.is_active:
            return
        remote_desc = RTCSessionDescription(sdp=answer_sdp, type="answer")
        await self.pc.setRemoteDescription(remote_desc)
        print(f"✅ [WebRTC Connected] Call established with '{self.host_name}'.")

        # Flush any queued candidates
        while self.pending_candidates:
            c = self.pending_candidates.pop(0)
            try:
                await self.pc.addIceCandidate(c)
            except Exception:
                pass

    async def handle_remote_ice_candidate(self, candidate_data):
        if not candidate_data:
            return
        try:
            cand = candidate_data
            if isinstance(cand, str):
                cand = json.loads(cand)

            cand_str = cand.get("candidate", "")
            sdp_mid = cand.get("sdpMid")
            sdp_mline_index = cand.get("sdpMLineIndex")

            from aiortc.sdp import candidate_from_sdp
            ice_cand = candidate_from_sdp(cand_str.replace("a=candidate:", ""))
            ice_cand.sdpMid = sdp_mid
            ice_cand.sdpMLineIndex = sdp_mline_index

            if self.pc and self.pc.remoteDescription:
                await self.pc.addIceCandidate(ice_cand)
            else:
                self.pending_candidates.append(ice_cand)
        except Exception:
            pass

    def hangup(self, notify_server=False):
        if not self.is_active:
            return
        self.is_active = False
        print(f"📴 [WebRTC Call Ended] Session with '{self.host_name}' closed.")

        if self.on_hangup:
            try:
                self.on_hangup()
            except Exception:
                pass

        if notify_server and self.server_url and self.client_uuid:
            try:
                payload = {
                    "action": "hangup",
                    "event": "hangup",
                    "type": "webrtc_call_hangup",
                    "uuid": self.client_uuid,
                    "token": self.client_token or "",
                    "host_uuid": self.host_uuid or "",
                    "call_id": self.call_id or "",
                    "status": "ended",
                    "reason": "local_hangup"
                }
                self._post_json("/api/host", payload)
            except Exception:
                pass

        if self.gui:
            self.gui.close()
            self.gui = None

        if self.audio_proc:
            try:
                if self.audio_proc.stdin:
                    self.audio_proc.stdin.close()
                self.audio_proc.terminate()
            except Exception:
                pass
            self.audio_proc = None

        for task in self.tasks:
            if not task.done():
                task.cancel()
        self.tasks.clear()

        if self.recorder and self.recorder.is_recording:
            rec_to_stop = self.recorder
            self.recorder = None
            if self.loop and self.loop.is_running():
                try:
                    asyncio.run_coroutine_threadsafe(rec_to_stop.stop(), self.loop)
                except Exception:
                    pass
            elif self.loop and not self.loop.is_closed():
                try:
                    self.loop.run_until_complete(rec_to_stop.stop())
                except Exception:
                    pass

        if self.pc:
            pc_to_close = self.pc
            self.pc = None
            if self.loop and self.loop.is_running():
                try:
                    asyncio.run_coroutine_threadsafe(pc_to_close.close(), self.loop)
                except Exception:
                    pass
            elif self.loop and not self.loop.is_closed():
                try:
                    self.loop.run_until_complete(pc_to_close.close())
                except Exception:
                    pass
