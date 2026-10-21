package yamux

import (
	"bytes"
	"errors"
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
	controlErr     chan error
	controlHdrLock sync.Mutex

	sendHdr  header
	sendErr  chan error
	sendLock sync.Mutex

	recvNotifyCh chan struct{}
	sendNotifyCh chan struct{}

	readDeadline  atomic.Value
	writeDeadline atomic.Value

	establishCh chan struct{}

	closeTimer *time.Timer
}

func newStream(session *Session, id uint32, state streamState) *Stream {
	s := &Stream{
		id:           id,
		session:      session,
		state:        state,
		controlHdr:   header(make([]byte, headerSize)),
		controlErr:   make(chan error, 1),
		sendHdr:      header(make([]byte, headerSize)),
		sendErr:      make(chan error, 1),
		recvWindow:   initialStreamWindow,
		sendWindow:   initialStreamWindow,
		recvNotifyCh: make(chan struct{}, 1),
		sendNotifyCh: make(chan struct{}, 1),
		establishCh:  make(chan struct{}, 1),
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
		s.recvLock.Unlock()
		goto WAIT
	}

	n, _ = s.recvBuf.Read(b)
	s.shrinkRecvBuf()
	s.recvLock.Unlock()

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

	window := atomic.LoadUint32(&s.sendWindow)
	if window == 0 {
		goto WAIT
	}

	flags = s.sendFlags()

	max = min(window, uint32(len(b)))
	body = b[:max]

	s.sendHdr.encode(typeData, flags, s.id, max)
	if err = s.session.waitForSendErr(s.sendHdr, body, s.sendErr); err != nil {
		if errors.Is(err, ErrSessionShutdown) || errors.Is(err, ErrConnectionWriteTimeout) {
			s.sendHdr = header(make([]byte, headerSize))
		}
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
	s.controlHdrLock.Lock()
	defer s.controlHdrLock.Unlock()

	var bufLen uint32
	s.recvLock.Lock()
	max := s.recvWindowMax
	if s.recvBuf != nil {
		bufLen = uint32(s.recvBuf.Len())
	}
	delta := s.recvDelta(max, bufLen)

	flags := s.sendFlags()

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
	if err := s.session.waitForSendErr(s.controlHdr, nil, s.controlErr); err != nil {
		if errors.Is(err, ErrSessionShutdown) || errors.Is(err, ErrConnectionWriteTimeout) {
			s.controlHdr = header(make([]byte, headerSize))
		}
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
	if err := s.session.waitForSendErr(s.controlHdr, nil, s.controlErr); err != nil {
		if errors.Is(err, ErrSessionShutdown) || errors.Is(err, ErrConnectionWriteTimeout) {
			s.controlHdr = header(make([]byte, headerSize))
		}
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

func (s *Stream) Abort() error {
	s.recvLock.Lock()
	pending := s.recvBuf != nil && s.recvBuf.Len() > 0
	s.recvDiscard = true
	s.recvLock.Unlock()
	if pending {
		return s.Reset()
	}
	return s.Close()
}

func (s *Stream) Reset() error {
	s.stateLock.Lock()
	switch s.state {
	case streamClosed, streamReset:
		s.stateLock.Unlock()
		return nil
	}
	s.state = streamReset
	if s.closeTimer != nil {
		s.closeTimer.Stop()
		s.closeTimer = nil
	}
	s.stateLock.Unlock()

	s.recvLock.Lock()
	s.recvBuf = nil
	s.recvLock.Unlock()
	s.notifyWaiting()
	s.session.closeStream(s.id)

	hdr := header(make([]byte, headerSize))
	hdr.encode(typeWindowUpdate, flagRST, s.id, 0)
	return s.session.sendNoWait(hdr)
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
		default:
			s.session.logger.Printf("[ERR] yamux: unexpected FIN flag in state %d", s.state)
			return ErrUnexpectedFlag
		}
	}
	if flags&flagRST == flagRST {
		s.state = streamReset
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
	if err := s.processFlags(flags); err != nil {
		return err
	}

	length := hdr.Length()
	if length == 0 {
		return nil
	}

	conn = &io.LimitedReader{R: conn, N: int64(length)}

	s.recvLock.Lock()

	if s.recvDiscard {
		s.recvLock.Unlock()
		if _, err := io.Copy(io.Discard, conn); err != nil {
			return err
		}
		s.Reset()
		return nil
	}

	if length > s.recvWindow {
		s.session.logger.Printf("[ERR] yamux: receive window exceeded (stream: %d, remain: %d, recv: %d)", s.id, s.recvWindow, length)
		s.recvLock.Unlock()
		return ErrRecvWindowExceeded
	}

	if s.recvBuf == nil {
		s.recvBuf = bytes.NewBuffer(make([]byte, 0, length))
	}
	copiedLength, err := io.Copy(s.recvBuf, conn)
	if err != nil {
		s.session.logger.Printf("[ERR] yamux: Failed to read stream data: %v", err)
		s.recvLock.Unlock()
		return err
	}

	s.recvWindow -= uint32(copiedLength)
	s.recvLock.Unlock()

	asyncNotify(s.recvNotifyCh)
	return nil
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
