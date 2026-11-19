package yamux

import (
	"bytes"
	"context"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

type streamState int

const (
	streamInit streamState = iota
	streamSYNSent
	streamSYNReceived
	streamEstablished
	streamLocalClose
	streamRemoteClose
	streamClosed
	streamReset
)

type Stream struct {
	recvWindow uint32
	sendWindow uint32

	recvWindowMax uint32
	recvWaited    bool
	recvDiscard   bool

	id      uint32
	session *Session

	state     streamState
	stateLock sync.Mutex

	recvBuf  *bytes.Buffer
	recvLock sync.Mutex

	controlHdr     header
	controlHdrLock sync.Mutex

	sendHdr  header
	sendLock sync.Mutex

	recvNotifyCh chan struct{}
	sendNotifyCh chan struct{}

	readDeadline  atomic.Value
	writeDeadline atomic.Value

	establishCh chan struct{}
	abortCh     chan struct{}
	abortOnce   sync.Once

	closeTimer *time.Timer
}

func newStream(session *Session, id uint32, state streamState) *Stream {
	s := &Stream{
		id:           id,
		session:      session,
		state:        state,
		controlHdr:   header(make([]byte, headerSize)),
		sendHdr:      header(make([]byte, headerSize)),
		recvWindow:   initialStreamWindow,
		sendWindow:   initialStreamWindow,
		recvNotifyCh: make(chan struct{}, 1),
		sendNotifyCh: make(chan struct{}, 1),
		establishCh:  make(chan struct{}, 1),
		abortCh:      make(chan struct{}),
	}
	s.recvWindowMax = session.config.MaxStreamWindowSize
	if start := session.config.StartStreamWindowSize; start > 0 && start < s.recvWindowMax {
		s.recvWindowMax = start
		if start < initialStreamWindow {
			s.recvWindowMax = initialStreamWindow
		}
	}
	s.readDeadline.Store(time.Time{})
	s.writeDeadline.Store(time.Time{})
	return s
}

const bigRecvBuf = 4 << 20

func (s *Stream) shrinkRecvBuf() {
	b := s.recvBuf
	if b == nil || b.Cap() <= bigRecvBuf || b.Len() > b.Cap()/8 {
		return
	}
	if b.Len() == 0 {
		s.recvBuf = nil
		return
	}
	nb := bytes.NewBuffer(make([]byte, 0, 2*b.Len()))
	nb.Write(b.Bytes())
	s.recvBuf = nb
}

func (s *Stream) recvDelta(max, bufLen uint32) uint32 {
	if used := bufLen + s.recvWindow; max > used {
		return max - used
	}
	return 0
}

func (s *Stream) Session() *Session {
	return s.session
}

func (s *Stream) StreamID() uint32 {
	return s.id
}

func (s *Stream) Read(b []byte) (n int, err error) {
	defer asyncNotify(s.recvNotifyCh)
START:

	s.stateLock.Lock()
	switch s.state {
	case streamLocalClose:
	case streamRemoteClose:
		fallthrough
	case streamClosed:
		s.recvLock.Lock()
		if s.recvBuf == nil || s.recvBuf.Len() == 0 {
			s.recvLock.Unlock()
			s.stateLock.Unlock()
			return 0, io.EOF
		}
		s.recvLock.Unlock()
	case streamReset:
		s.stateLock.Unlock()
		return 0, ErrConnectionReset
	}
	s.stateLock.Unlock()

	s.recvLock.Lock()
	if s.recvBuf == nil || s.recvBuf.Len() == 0 {
		s.recvWaited = true
		refill := s.recvWindow == 0
		s.recvLock.Unlock()
		if refill {
			// A queued credit update may have timed out after the last bytes
			// were consumed. Retry it before waiting: the peer has no credit
			// left to send the data that would otherwise wake this reader.
			if err := s.sendWindowUpdate(); err != nil {
				return 0, err
			}
			goto START
		}
		goto WAIT
	}

	n, _ = s.recvBuf.Read(b)
	s.shrinkRecvBuf()
	s.recvLock.Unlock()

	if s.session.IsClosed() {
		return n, nil
	}
	err = s.sendWindowUpdate()
	if err == ErrSessionShutdown {
		err = nil
	}
	return n, err

WAIT:
	var timeout <-chan time.Time
	var timer *time.Timer
	readDeadline := s.readDeadline.Load().(time.Time)
	if !readDeadline.IsZero() {
		delay := time.Until(readDeadline)
		timer = time.NewTimer(delay)
		timeout = timer.C
	}
	select {
	case <-s.session.shutdownCh:
	case <-s.recvNotifyCh:
	case <-timeout:
		return 0, ErrTimeout
	}
	if timer != nil {
		if !timer.Stop() {
			<-timeout
		}
	}
	goto START
}

func (s *Stream) Write(b []byte) (n int, err error) {
	s.sendLock.Lock()
	defer s.sendLock.Unlock()
	total := 0
	for total < len(b) {
		n, err := s.write(b[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

func (s *Stream) write(b []byte) (n int, err error) {
	var flags uint16
	var max uint32
	var body []byte
START:
	s.stateLock.Lock()
	switch s.state {
	case streamLocalClose:
		fallthrough
	case streamClosed:
		s.stateLock.Unlock()
		return 0, ErrStreamClosed
	case streamReset:
		s.stateLock.Unlock()
		return 0, ErrConnectionReset
	}
	s.stateLock.Unlock()

	if deadline := s.writeDeadline.Load().(time.Time); !deadline.IsZero() && !time.Now().Before(deadline) {
		return 0, ErrTimeout
	}
	window := atomic.LoadUint32(&s.sendWindow)
	if window == 0 {
		goto WAIT
	}

	flags = s.sendFlags()

	max = min(window, uint32(len(b)))
	body = b[:max]

	s.sendHdr.encode(typeData, flags, s.id, max)
	if err = s.session.waitForSendErr(s.sendHdr, body, sendWait{
		closed: s.abortCh, notify: s.sendNotifyCh,
		deadline: func() time.Time { return s.writeDeadline.Load().(time.Time) },
	}); err != nil {
		return 0, err
	}

	atomic.AddUint32(&s.sendWindow, ^uint32(max-1))

	return int(max), err

WAIT:
	var timeout <-chan time.Time
	var timer *time.Timer
	writeDeadline := s.writeDeadline.Load().(time.Time)
	if !writeDeadline.IsZero() {
		delay := time.Until(writeDeadline)
		timer = time.NewTimer(delay)
		timeout = timer.C
	}
	select {
	case <-s.session.shutdownCh:
	case <-s.sendNotifyCh:
	case <-timeout:
		return 0, ErrTimeout
	}
	if timer != nil {
		if !timer.Stop() {
			<-timeout
		}
	}
	goto START
}

func (s *Stream) sendFlags() uint16 {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	var flags uint16
	switch s.state {
	case streamInit:
		flags |= flagSYN
		s.state = streamSYNSent
	case streamSYNReceived:
		flags |= flagACK
		s.state = streamEstablished
	}
	return flags
}

func (s *Stream) sendWindowUpdate() error {
	return s.sendWindowUpdateContext(context.Background())
}

func (s *Stream) sendWindowUpdateContext(ctx context.Context) error {
	s.controlHdrLock.Lock()
	defer s.controlHdrLock.Unlock()

	flags := s.sendFlags()
	var bufLen uint32
	s.recvLock.Lock()
	max := s.recvWindowMax
	if s.recvBuf != nil {
		bufLen = uint32(s.recvBuf.Len())
	}
	delta := s.recvDelta(max, bufLen)

	if delta < (max/2) && flags == 0 {
		s.recvLock.Unlock()
		return nil
	}

	if start := s.session.config.StartStreamWindowSize; start > 0 && flags == 0 {
		if s.recvWaited {
			max = min(2*max, s.session.config.MaxStreamWindowSize)
		} else if max/2 >= start {
			max /= 2
		}
		s.recvWindowMax = max
		if delta = s.recvDelta(max, bufLen); delta == 0 {
			s.recvWaited = false
			s.recvLock.Unlock()
			return nil
		}
	}
	s.recvWaited = false

	s.recvWindow += delta
	s.recvLock.Unlock()

	s.controlHdr.encode(typeWindowUpdate, flags, s.id, delta)
	if err := s.session.waitForSendErr(s.controlHdr, nil, sendWait{
		ctx: ctx, closed: s.abortCh, notify: s.recvNotifyCh,
		deadline: func() time.Time { return s.readDeadline.Load().(time.Time) },
	}); err != nil {
		// A canceled queued update advertised no credit. Roll it back so a
		// caller can refresh its read deadline and continue the same stream.
		// A failed in-flight frame has already closed the shared transport.
		s.recvLock.Lock()
		s.recvWindow -= delta
		s.recvLock.Unlock()
		return err
	}
	return nil
}

func (s *Stream) sendClose() error {
	s.controlHdrLock.Lock()
	defer s.controlHdrLock.Unlock()

	flags := s.sendFlags()
	flags |= flagFIN
	s.controlHdr.encode(typeWindowUpdate, flags, s.id, 0)
	if err := s.session.waitForSendErr(s.controlHdr, nil, sendWait{closed: s.abortCh}); err != nil {
		return err
	}
	return nil
}

func (s *Stream) Close() error {
	closeStream := false
	s.stateLock.Lock()
	switch s.state {
	case streamSYNSent:
		fallthrough
	case streamSYNReceived:
		fallthrough
	case streamEstablished:
		s.state = streamLocalClose
		goto SEND_CLOSE

	case streamLocalClose:
	case streamRemoteClose:
		s.state = streamClosed
		closeStream = true
		goto SEND_CLOSE

	case streamClosed:
	case streamReset:
	default:
		panic("unhandled state")
	}
	s.stateLock.Unlock()
	return nil
SEND_CLOSE:
	if s.closeTimer != nil {
		s.closeTimer.Stop()
		s.closeTimer = nil
	}

	if !closeStream && s.session.config.StreamCloseTimeout > 0 {
		s.closeTimer = time.AfterFunc(
			s.session.config.StreamCloseTimeout, s.closeTimeout)
	}

	s.stateLock.Unlock()
	s.sendClose()
	s.notifyWaiting()
	if closeStream {
		s.session.closeStream(s.id)
	}
	return nil
}

// Abort ends both directions immediately. Close remains a graceful half-close.
func (s *Stream) Abort() error { return s.Reset() }

func (s *Stream) Reset() error {
	s.stateLock.Lock()
	closed := s.state == streamClosed
	switch s.state {
	case streamReset:
		s.stateLock.Unlock()
		return nil
	}
	s.state = streamReset
	s.abortOnce.Do(func() { close(s.abortCh) })
	if s.closeTimer != nil {
		s.closeTimer.Stop()
		s.closeTimer = nil
	}
	s.stateLock.Unlock()

	s.recvLock.Lock()
	s.recvDiscard = true
	s.recvBuf = nil
	s.recvLock.Unlock()
	s.notifyWaiting()
	s.session.closeStream(s.id)
	if closed {
		return nil
	}

	hdr := header(make([]byte, headerSize))
	hdr.encode(typeWindowUpdate, flagRST, s.id, 0)
	// Local cancellation must not wait behind another stream's frame. This
	// bounded notification may wait for queue space, but no caller I/O does.
	go func() { _ = s.session.sendNoWait(hdr) }()
	return nil
}

func (s *Stream) closeTimeout() {
	s.forceClose()

	s.session.closeStream(s.id)

	s.sendLock.Lock()
	defer s.sendLock.Unlock()
	hdr := header(make([]byte, headerSize))
	hdr.encode(typeWindowUpdate, flagRST, s.id, 0)
	_ = s.session.sendNoWait(hdr)
}

func (s *Stream) forceClose() {
	s.stateLock.Lock()
	s.state = streamClosed
	s.abortOnce.Do(func() { close(s.abortCh) })
	s.stateLock.Unlock()
	s.notifyWaiting()
}

func (s *Stream) processFlags(flags uint16) error {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()

	closeStream := false
	defer func() {
		if closeStream {
			if s.closeTimer != nil {
				s.closeTimer.Stop()
			}

			s.session.closeStream(s.id)
		}
	}()

	if flags&flagACK == flagACK {
		if s.state == streamSYNSent {
			s.state = streamEstablished
		}
		asyncNotify(s.establishCh)
		s.session.establishStream(s.id)
	}
	if flags&flagFIN == flagFIN {
		switch s.state {
		case streamSYNSent:
			fallthrough
		case streamSYNReceived:
			fallthrough
		case streamEstablished:
			s.state = streamRemoteClose
			s.notifyWaiting()
		case streamLocalClose:
			s.state = streamClosed
			closeStream = true
			s.notifyWaiting()
		case streamReset:
			// Local Abort may race a FIN whose frame is already being read.
			// The reset stream is gone; that FIN must not kill its siblings.
		default:
			s.session.logger.Printf("[ERR] yamux: unexpected FIN flag in state %d", s.state)
			return ErrUnexpectedFlag
		}
	}
	if flags&flagRST == flagRST {
		s.state = streamReset
		s.abortOnce.Do(func() { close(s.abortCh) })
		s.recvLock.Lock()
		s.recvDiscard, s.recvBuf = true, nil
		s.recvLock.Unlock()
		closeStream = true
		s.notifyWaiting()
	}
	return nil
}

func (s *Stream) notifyWaiting() {
	asyncNotify(s.recvNotifyCh)
	asyncNotify(s.sendNotifyCh)
	asyncNotify(s.establishCh)
}

func (s *Stream) incrSendWindow(hdr header, flags uint16) error {
	if err := s.processFlags(flags); err != nil {
		return err
	}

	atomic.AddUint32(&s.sendWindow, hdr.Length())
	asyncNotify(s.sendNotifyCh)
	return nil
}

func (s *Stream) readData(hdr header, flags uint16, conn io.Reader) error {
	// FIN belongs after the body; publishing it first can let a reader see
	// EOF before the final data is in its buffer.
	if err := s.processFlags(flags &^ flagFIN); err != nil {
		return err
	}

	length := hdr.Length()
	if length == 0 {
		return s.processFlags(flags & flagFIN)
	}
	s.recvLock.Lock()
	if !s.recvDiscard && length > s.recvWindow {
		s.session.logger.Printf("[ERR] yamux: receive window exceeded (stream: %d, remain: %d, recv: %d)", s.id, s.recvWindow, length)
		s.recvLock.Unlock()
		return ErrRecvWindowExceeded
	}
	s.recvLock.Unlock()
	for remaining := length; remaining > 0; {
		// Only the session receive loop uses recvBuffer. Never hold recvLock
		// during socket I/O: an incomplete frame must not block local Abort.
		buf := s.session.recvBuffer[:min(remaining, uint32(len(s.session.recvBuffer)))]
		n, err := conn.Read(buf)
		if n > 0 {
			s.recvLock.Lock()
			if !s.recvDiscard {
				if s.recvBuf == nil {
					s.recvBuf = bytes.NewBuffer(make([]byte, 0, n))
				}
				s.recvBuf.Write(buf[:n])
				s.recvWindow -= uint32(n)
			}
			s.recvLock.Unlock()
			remaining -= uint32(n)
			asyncNotify(s.recvNotifyCh)
		}
		if err != nil {
			if err == io.EOF && remaining > 0 {
				err = io.ErrUnexpectedEOF
			}
			return err
		}
	}
	s.recvLock.Lock()
	discard := s.recvDiscard
	s.recvLock.Unlock()
	if discard {
		return nil
	}
	return s.processFlags(flags & flagFIN)
}

func (s *Stream) SetDeadline(t time.Time) error {
	if err := s.SetReadDeadline(t); err != nil {
		return err
	}
	if err := s.SetWriteDeadline(t); err != nil {
		return err
	}
	return nil
}

func (s *Stream) SetReadDeadline(t time.Time) error {
	s.readDeadline.Store(t)
	asyncNotify(s.recvNotifyCh)
	return nil
}

func (s *Stream) SetWriteDeadline(t time.Time) error {
	s.writeDeadline.Store(t)
	asyncNotify(s.sendNotifyCh)
	return nil
}

func (s *Stream) Shrink() {
	s.recvLock.Lock()
	if s.recvBuf != nil && s.recvBuf.Len() == 0 {
		s.recvBuf = nil
	}
	s.recvLock.Unlock()
}
