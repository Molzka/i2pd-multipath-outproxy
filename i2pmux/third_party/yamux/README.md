github.com/hashicorp/yamux v0.1.2 (MPL-2.0, see LICENSE) with two changes in
`stream.go` / `mux.go`:

- `Config.StartStreamWindowSize`: receive window auto-tuning. A stream starts
  with this window and doubles it up to `MaxStreamWindowSize` only while the
  sender keeps running out of window and the reader keeps its buffer drained.
  A download over the aggregated I2P links needs a window of tens of MB
  (round trip with reordering is seconds), but a fixed window that large lets
  every slow reader (paused video, background app) park that much in memory.
- a drained receive buffer above 4 MB is dropped instead of being kept for the
  lifetime of the stream.

Tests of the original module are not copied.

Additional lifecycle fixes preserve the yamux wire format:

- `OpenStreamContext(ctx)` bounds waiting for a SYN slot and sending the
  opening frame. Like `OpenStream`, it returns before the peer's ACK.
- `Close()` remains the graceful write half-close used by the relay.
  `Abort()` / `Reset()` close both local directions and wake blocked I/O,
  including when the receive buffer is empty or an incoming frame is partial.
- Stream deadlines apply while frames wait in the session send queue, and
  changing a write deadline affects the pending write. Canceling a queued
  frame does not terminate sibling streams. Once a transport write starts,
  cancellation closes the session: abandoning a partial frame would corrupt
  framing for every stream. Each queued frame has its own completion result.
- Incoming data is read in bounded chunks without holding the stream's
  receive lock across socket I/O. FIN is applied after its accompanying data.
