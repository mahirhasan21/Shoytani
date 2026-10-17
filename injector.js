(() => {
  if (window.__micMaxInjectorReady) return;
  window.__micMaxInjectorReady = true;

  // Extreme profile based on Omni DC Lord, with clamps to keep controls recoverable.
  const DEFAULTS = {
    enabled: true,
    gainDb: 84,
    thresholdDb: -60,
    knee: 40,
    ratio: 20,
    attack: 0.0001,
    release: 0.03,
    lowShelfDb: 14,
    presenceDb: 20,
    highShelfDb: 16,
    limiterDb: -0.1,
    drive: 1.8,
    loudness: 20.0,
    maxBoost: 5000,
    sustain: true,
    sustainTargetDb: -2,
    sustainMaxGain: 64,
    forceRawMic: true,
    punch: 6,
    listenGain: 1,
    listenClarity: 6,
    listenBass: 4,
    listenPunch: 5,
    micBoost: 4
  };
  const MSG_CFG = 'MIC_MAXIMIZER_CONFIG';
  const AUDIO_SEND_MAX_BITRATE = 510000;
  const state = {
    config: { ...DEFAULTS },
    origMD: null,
    origLegacy: null,
    pipelines: new Set(),
    trackMap: new WeakMap(),
    processedTracks: new WeakSet(),
    processedMeta: new WeakMap(),
    senderWatchTracks: new WeakSet(),
    peerConnections: new Set(),
    senderRecords: new Set(),
    senderBySender: new WeakMap(),
    refreshingSenders: new WeakSet(),
    recoverTimers: new Set(),
    sourceTracks: new Set(),
    origApplyConstraints: null,
    lastAudioConstraints: { audio: true }
  };
  const clamp = (value, min, max) => Math.min(max, Math.max(min, Number.isFinite(Number(value)) ? Number(value) : min));
  const dbToLinear = (db) => Math.pow(10, db / 20);

  function cfg(input = state.config) {
    const merged = { ...DEFAULTS, ...(input || {}) };
    merged.enabled = Boolean(merged.enabled);
    merged.maxBoost = clamp(merged.maxBoost, 1, 5000);
    merged.loudness = clamp(merged.loudness, 0.5, merged.maxBoost);
    merged.gainDb = clamp(merged.gainDb, 0, 120);
    merged.drive = clamp(merged.drive, 0, 10);
    merged.thresholdDb = clamp(merged.thresholdDb, -100, 0);
    merged.knee = clamp(merged.knee, 0, 40);
    // DynamicsCompressorNode.ratio has a nominal browser range of [1, 20].
    // Values above 20 trigger Quetta/Chromium extension errors/warnings at setTargetAtTime.
    merged.ratio = clamp(merged.ratio, 1, 20);
    merged.attack = clamp(merged.attack, 0.0001, 1);
    merged.release = clamp(merged.release, 0.01, 1);
    merged.lowShelfDb = clamp(merged.lowShelfDb, -60, 60);
    merged.presenceDb = clamp(merged.presenceDb, -60, 60);
    merged.highShelfDb = clamp(merged.highShelfDb, -60, 60);
    merged.limiterDb = clamp(merged.limiterDb, -24, 0);
    merged.sustain = Boolean(merged.sustain);
    merged.sustainTargetDb = clamp(merged.sustainTargetDb, -24, -1);
    merged.sustainMaxGain = clamp(merged.sustainMaxGain, 1, 64);
    merged.forceRawMic = Boolean(merged.forceRawMic);
    merged.punch = clamp(merged.punch, 1, 24);
    merged.listenGain = clamp(merged.listenGain, 1, 1000);
    merged.listenPunch = clamp(merged.listenPunch, 1, 10);
    merged.micBoost = clamp(merged.micBoost, 1, 1000);
    merged.listenClarity = clamp(merged.listenClarity, 0, 10);
    merged.listenBass = clamp(merged.listenBass, 0, 12);
    return merged;
  }

  function makeSaturationCurve(amount = 0.5) {
    const k = Math.max(0.0001, amount * 100);
    const n = 4096;
    const curve = new Float32Array(n);
    for (let i = 0; i < n; i += 1) {
      const x = (i * 2) / n - 1;
      curve[i] = ((Math.PI + k) * x) / (Math.PI + k * Math.abs(x));
    }
    return curve;
  }

  // Clip curve: k small = soft rounding, k large = hard clip (loudest). Never exceeds full scale.
  function makeSoftClipCurve(k = 1.6) {
    const n = 2048;
    const curve = new Float32Array(n);
    const norm = Math.tanh(k);
    for (let i = 0; i < n; i += 1) {
      const x = (i * 2) / (n - 1) - 1;
      curve[i] = Math.tanh(k * x) / norm;
    }
    return curve;
  }

  function setParam(param, value, ctx) {
    if (!param) return;
    const safeValue = clamp(value, param.minValue ?? -Infinity, param.maxValue ?? Infinity);
    const now = ctx?.currentTime || 0;
    try {
      if (typeof param.cancelScheduledValues === 'function') param.cancelScheduledValues(now);
      if (typeof param.setTargetAtTime === 'function') param.setTargetAtTime(safeValue, now, 0.005);
      else param.value = safeValue;
    } catch (_) {
      try { param.value = safeValue; } catch (_) {}
    }
  }

  function applyPipeline(pipeline, inputConfig = state.config) {
    const raw = cfg(inputConfig);
    const c = raw.enabled ? raw : {
      ...raw,
      lowShelfDb: 0,
      presenceDb: 0,
      highShelfDb: 0,
      thresholdDb: -6,
      knee: 0,
      ratio: 1,
      loudness: 1,
      gainDb: 0,
      drive: 0,
      punch: 1,
      micBoost: 1,
      limiterDb: -0.5
    };
    const { ctx, nodes } = pipeline;
    setParam(nodes.low.gain, c.lowShelfDb, ctx);
    setParam(nodes.pres.gain, c.presenceDb, ctx);
    setParam(nodes.high.gain, c.highShelfDb, ctx);
    setParam(nodes.comp1.threshold, c.thresholdDb, ctx);
    setParam(nodes.comp1.knee, c.knee, ctx);
    setParam(nodes.comp1.ratio, c.ratio, ctx);
    setParam(nodes.comp1.attack, c.attack, ctx);
    setParam(nodes.comp1.release, c.release, ctx);
    setParam(nodes.loudness.gain, c.loudness, ctx);
    setParam(nodes.gain.gain, dbToLinear(c.gainDb), ctx);
    if (nodes.boost) setParam(nodes.boost.gain, c.micBoost, ctx);
    if (nodes.punch) setParam(nodes.punch.gain, c.punch, ctx);
    nodes.saturator.curve = makeSaturationCurve(c.drive);
    if (nodes.sustain && !c.sustain) setParam(nodes.sustain.gain, 1, ctx);
    setParam(nodes.limiter.threshold, c.limiterDb, ctx);
  }

  function updateAllPipelines(inputConfig = state.config) {
    for (const pipeline of state.pipelines) applyPipeline(pipeline, inputConfig);
  }

  function resumePipeline(pipeline) {
    const ctx = pipeline?.ctx;
    if (!ctx || ctx.state === 'closed' || typeof ctx.resume !== 'function') return;
    if (ctx.state !== 'running') ctx.resume().catch(() => {});
  }

  function resumeAllPipelines() {
    for (const pipeline of state.pipelines) resumePipeline(pipeline);
  }

  function rmsDbFromAnalyser(analyser, buffer) {
    analyser.getByteTimeDomainData(buffer);
    let sum = 0;
    for (let i = 0; i < buffer.length; i += 1) {
      const sample = (buffer[i] - 128) / 128;
      sum += sample * sample;
    }
    const rms = Math.sqrt(sum / buffer.length);
    return 20 * Math.log10(Math.max(rms, 0.000001));
  }

  function startSustainController(pipeline) {
    const { ctx, nodes } = pipeline;
    if (!nodes.meter || !nodes.sustain) return;
    const buffer = new Uint8Array(nodes.meter.fftSize);
    let currentGain = 1;
    pipeline.sustainTimer = setInterval(() => {
      const c = cfg();
      if (!c.enabled || !c.sustain || ctx.state === 'closed') {
        currentGain = 1;
        setParam(nodes.sustain.gain, 1, ctx);
        return;
      }
      resumePipeline(pipeline);
      const db = rmsDbFromAnalyser(nodes.meter, buffer);
      const target = c.sustainTargetDb;
      if (db < target) {
        const lift = 1 + Math.min(1.2, Math.max(0.02, (target - db) * 0.035));
        currentGain = Math.min(c.sustainMaxGain, currentGain * lift);
      } else {
        currentGain = Math.max(1, currentGain * 0.82);
      }
      setParam(nodes.sustain.gain, currentGain, ctx);
    }, 120);
  }

  function createAudioContext() {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    try {
      return new AC({ latencyHint: 'interactive', sampleRate: 48000 });
    } catch (_) {
      return new AC({ latencyHint: 'interactive' });
    }
  }

  function build(stream, inputConfig) {
    const ctx = createAudioContext();
    if (!ctx || !stream.getAudioTracks().length) return stream;
    stream.getAudioTracks().forEach(enforceRawMicTrack);

    const source = ctx.createMediaStreamSource(stream);
    const hp = ctx.createBiquadFilter();
    hp.type = 'highpass';
    hp.frequency.value = 110;
    hp.Q.value = 0.7;

    const mud = ctx.createBiquadFilter();
    mud.type = 'peaking';
    mud.frequency.value = 320;
    mud.Q.value = 1;
    mud.gain.value = -4;

    const vox = ctx.createBiquadFilter();
    vox.type = 'peaking';
    vox.frequency.value = 1800;
    vox.Q.value = 1;
    vox.gain.value = 3;

    const vox2 = ctx.createBiquadFilter();
    vox2.type = 'peaking';
    vox2.frequency.value = 2400;
    vox2.Q.value = 0.9;
    vox2.gain.value = 5;

    const vox3 = ctx.createBiquadFilter();
    vox3.type = 'peaking';
    vox3.frequency.value = 4200;
    vox3.Q.value = 1.1;
    vox3.gain.value = 3;

    const low = ctx.createBiquadFilter();
    low.type = 'lowshelf';
    low.frequency.value = 200;

    const pres = ctx.createBiquadFilter();
    pres.type = 'peaking';
    pres.frequency.value = 3200;
    pres.Q.value = 1.5;

    const high = ctx.createBiquadFilter();
    high.type = 'highshelf';
    high.frequency.value = 6000;

    const comp1 = ctx.createDynamicsCompressor();
    const comp2 = ctx.createDynamicsCompressor();
    comp2.threshold.value = -18;
    comp2.knee.value = 5;
    comp2.ratio.value = 20;
    comp2.attack.value = 0.001;
    comp2.release.value = 0.05;

    const loudness = ctx.createGain();
    const gain = ctx.createGain();
    const boost = ctx.createGain();
    boost.gain.value = 1;
    const saturator = ctx.createWaveShaper();
    saturator.oversample = '4x';
    const sustain = ctx.createGain();
    sustain.gain.value = 1;

    const limiter = ctx.createDynamicsCompressor();
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.0001;
    limiter.release.value = 0.01;

    const meter = ctx.createAnalyser();
    meter.fftSize = 1024;
    meter.smoothingTimeConstant = 0.18;

    // SHOYTANI SHOKTI v6: final punch stage (gain into a hard clipper = maximum perceived loudness)
    const punch = ctx.createGain();
    punch.gain.value = 1;
    const voiceLift = ctx.createGain();
    voiceLift.gain.value = 4.2;
    const ceiling = ctx.createWaveShaper();
    ceiling.curve = makeSoftClipCurve(12);
    ceiling.oversample = '4x';
    const meterMute = ctx.createGain();
    meterMute.gain.value = 0;

    const dst = ctx.createMediaStreamDestination();
    source.connect(hp);
    hp.connect(mud);
    mud.connect(vox);
    vox.connect(vox2);
    vox2.connect(vox3);
    vox3.connect(low);
    low.connect(pres);
    pres.connect(high);
    high.connect(comp1);
    comp1.connect(comp2);
    comp2.connect(loudness);
    loudness.connect(gain);
    gain.connect(boost);
    boost.connect(saturator);
    saturator.connect(sustain);
    sustain.connect(limiter);
    limiter.connect(punch);
    punch.connect(voiceLift);
    voiceLift.connect(ceiling);
    ceiling.connect(dst);
    limiter.connect(meter);
    meter.connect(meterMute);
    meterMute.connect(dst);

    const pipeline = { ctx, nodes: { low, pres, high, comp1, loudness, gain, boost, saturator, sustain, limiter, meter, punch }, sustainTimer: null };
    applyPipeline(pipeline, inputConfig);
    state.pipelines.add(pipeline);
    startSustainController(pipeline);
    resumePipeline(pipeline);

    const outAudioTracks = dst.stream.getAudioTracks();
    outAudioTracks.forEach((track) => {
      state.processedTracks.add(track);
      state.processedMeta.set(track, { source: stream, pipeline });
    });

    const out = new MediaStream([
      ...outAudioTracks,
      ...stream.getTracks().filter((track) => track.kind !== 'audio')
    ]);

    const stop = () => {
      state.pipelines.delete(pipeline);
      if (pipeline.sustainTimer) clearInterval(pipeline.sustainTimer);
      stream.getAudioTracks().forEach((track) => state.sourceTracks.delete(track));
      try { ctx.close(); } catch (_) {}
    };
    stream.getTracks().forEach((track) => track.addEventListener('ended', stop, { once: true }));
    return out;
  }

  function rawMicAudioConstraints(audio = {}) {
    const base = audio && typeof audio === 'object' ? audio : {};
    return {
      ...base,
      echoCancellation: false,
      noiseSuppression: false,
      autoGainControl: false,
      googAutoGainControl: false,
      googNoiseSuppression: false,
      googHighpassFilter: false,
      googEchoCancellation: false,
      googEchoCancellation2: false,
      googDAEchoCancellation: false,
      googTypingNoiseDetection: false,
      googAudioMirroring: false,
      channelCount: { ideal: 1 },
      sampleRate: { ideal: 48000 },
      sampleSize: { ideal: 16 }
    };
  }

  function enforceRawMicTrack(track) {
    if (!track || track.kind !== 'audio' || !cfg().forceRawMic) return;
    state.sourceTracks.add(track);
    try { track.contentHint = 'speech'; } catch (_) {}
    if (typeof track.applyConstraints === 'function') {
      try { track.applyConstraints(rawMicAudioConstraints()).catch(() => {}); } catch (_) {}
    }
  }

  function enforceAllSourceConstraints() {
    for (const track of [...state.sourceTracks]) {
      if (!track || track.readyState === 'ended') {
        state.sourceTracks.delete(track);
        continue;
      }
      enforceRawMicTrack(track);
    }
  }

  function patchTrackConstraints() {
    const proto = window.MediaStreamTrack?.prototype;
    if (!proto || proto.__micMaxTrackPatched || typeof proto.applyConstraints !== 'function') return;
    state.origApplyConstraints = proto.applyConstraints;
    proto.applyConstraints = function applyConstraints(constraints = {}) {
      const next = cfg().enabled && cfg().forceRawMic && this.kind === 'audio'
        ? rawMicAudioConstraints(constraints)
        : constraints;
      return state.origApplyConstraints.call(this, next);
    };
    proto.__micMaxTrackPatched = true;
  }

  function normalizeConstraints(constraints) {
    if (constraints === true) constraints = { audio: true };
    if (!constraints || typeof constraints !== 'object') return constraints;
    const next = { ...constraints };
    if (next.audio === true) next.audio = {};
    if (typeof next.audio === 'object') next.audio = rawMicAudioConstraints(next.audio);
    return next;
  }

  function wantsAudio(constraints) {
    if (constraints === true) return true;
    if (!constraints || typeof constraints !== 'object') return false;
    return 'audio' in constraints ? Boolean(constraints.audio) : true;
  }

  function processedStreamFor(originalStream, rawTrack, processedTrack) {
    if (!originalStream || typeof originalStream.getTracks !== 'function') return new MediaStream([processedTrack]);
    return new MediaStream(originalStream.getTracks().map((track) => (track === rawTrack ? processedTrack : track)));
  }

  function liveAudioTrack(stream) {
    if (!stream || typeof stream.getAudioTracks !== 'function') return null;
    return stream.getAudioTracks().find((track) => track.readyState !== 'ended') || null;
  }

  function processedSourceIsLive(track) {
    if (!track || !state.processedTracks.has(track)) return true;
    const meta = state.processedMeta.get(track);
    if (!meta) return true;
    resumePipeline(meta.pipeline);
    return Boolean(liveAudioTrack(meta.source));
  }

  function trackNeedsRefresh(track) {
    if (!track || track.kind !== 'audio') return true;
    if (track.readyState === 'ended') return true;
    if (!state.processedTracks.has(track)) return true;
    return !processedSourceIsLive(track);
  }

  function rebuildProcessedTrack(track) {
    const meta = state.processedMeta.get(track);
    const sourceTrack = liveAudioTrack(meta?.source);
    if (!sourceTrack) return track;
    try {
      const rebuiltStream = build(new MediaStream([sourceTrack]), state.config);
      return liveAudioTrack(rebuiltStream) || track;
    } catch (_) {
      return track;
    }
  }

  function cloneForSender(track) {
    const liveTrack = track?.readyState === 'ended' ? rebuildProcessedTrack(track) : track;
    if (!liveTrack || liveTrack.readyState === 'ended' || typeof liveTrack.clone !== 'function') return liveTrack;
    try {
      const clone = liveTrack.clone();
      state.processedTracks.add(clone);
      const meta = state.processedMeta.get(liveTrack);
      if (meta) state.processedMeta.set(clone, meta);
      return clone;
    } catch (_) {
      return liveTrack;
    }
  }

  function processAudioTrack(track, forSender = false) {
    if (!track || track.kind !== 'audio') return track;
    if (state.processedTracks.has(track)) {
      const nextTrack = track.readyState === 'ended' ? rebuildProcessedTrack(track) : track;
      return forSender ? cloneForSender(nextTrack) : nextTrack;
    }

    const existing = state.trackMap.get(track);
    if (existing) {
      const nextTrack = existing.readyState === 'ended' ? rebuildProcessedTrack(existing) : existing;
      if (nextTrack && nextTrack !== existing && nextTrack.readyState !== 'ended') state.trackMap.set(track, nextTrack);
      if (nextTrack && nextTrack.readyState !== 'ended') return forSender ? cloneForSender(nextTrack) : nextTrack;
    }

    const processedStream = build(new MediaStream([track]), state.config);
    const processedTrack = liveAudioTrack(processedStream) || track;
    if (processedTrack !== track) {
      state.processedTracks.add(processedTrack);
      state.trackMap.set(track, processedTrack);
      track.addEventListener('ended', () => {
        try { processedTrack.stop(); } catch (_) {}
      }, { once: true });
    }
    return forSender ? cloneForSender(processedTrack) : processedTrack;
  }

  function tuneAudioSender(sender) {
    if (!sender || typeof sender.getParameters !== 'function' || typeof sender.setParameters !== 'function') return;
    try {
      const params = sender.getParameters() || {};
      const encodings = Array.isArray(params.encodings) && params.encodings.length ? params.encodings : [{}];
      params.encodings = encodings.map((encoding) => ({
        ...encoding,
        active: encoding.active !== false,
        dtx: false,
        maxBitrate: Math.max(Number(encoding.maxBitrate) || 0, AUDIO_SEND_MAX_BITRATE),
        networkPriority: 'high',
        priority: 'high'
      }));
      sender.setParameters(params).catch(() => {});
    } catch (_) {}
  }

  function rememberPeerConnection(pc) {
    if (!pc || state.peerConnections.has(pc)) return;
    state.peerConnections.add(pc);
    if (typeof pc.addEventListener === 'function') {
      pc.addEventListener('connectionstatechange', () => {
        if (['closed', 'failed'].includes(pc.connectionState)) state.peerConnections.delete(pc);
      });
    }
  }

  function rememberSender(sender, track, pc = null) {
    if (!sender) return null;
    let record = state.senderBySender.get(sender);
    if (!record) {
      record = { sender, track: null, pc: null };
      state.senderBySender.set(sender, record);
      state.senderRecords.add(record);
    }
    if (track) record.track = track;
    if (pc) record.pc = pc;
    return record;
  }

  function recordIsClosed(record) {
    const pc = record?.pc;
    if (!pc) return false;
    return ['closed', 'failed'].includes(pc.connectionState || pc.iceConnectionState || '');
  }

  async function reacquireProcessedTrackForSender() {
    if (!state.origMD) return null;
    try {
      const constraints = normalizeConstraints(state.lastAudioConstraints || { audio: true });
      const stream = await state.origMD(constraints);
      const rawTrack = liveAudioTrack(stream);
      if (!rawTrack) return null;
      return processAudioTrack(rawTrack, true);
    } catch (_) {
      return null;
    }
  }

  async function replaceSenderTrack(sender, track) {
    if (!sender || typeof sender.replaceTrack !== 'function') return null;
    rememberSender(sender, track);
    const current = track || sender.track;
    let replacement = null;

    if (current && current.kind === 'audio' && !trackNeedsRefresh(current)) {
      replacement = current;
    } else if (current && current.kind === 'audio' && current.readyState !== 'ended' && !state.processedTracks.has(current)) {
      replacement = processAudioTrack(current, true);
    }

    if (!replacement || trackNeedsRefresh(replacement)) replacement = await reacquireProcessedTrackForSender();
    if (!replacement || replacement.readyState === 'ended') return null;

    try {
      await sender.replaceTrack(replacement);
      tuneAudioSender(sender);
      rememberSender(sender, replacement);
      watchSenderTrack(sender, replacement);
      return replacement;
    } catch (_) {
      return null;
    }
  }

  function queueSenderRefresh(sender, track) {
    resumeAllPipelines();
    if (!sender || state.refreshingSenders.has(sender)) return;
    state.refreshingSenders.add(sender);
    setTimeout(() => {
      replaceSenderTrack(sender, track).finally(() => state.refreshingSenders.delete(sender));
    }, 50);
  }

  function watchSenderTrack(sender, track) {
    if (!sender || !track || track.kind !== 'audio') return;
    rememberSender(sender, track);
    if (!state.processedTracks.has(track) || state.senderWatchTracks.has(track)) return;
    state.senderWatchTracks.add(track);
    track.addEventListener('ended', () => queueSenderRefresh(sender, track), { once: true });
  }

  function scheduleRecoveryPasses() {
    for (const timer of state.recoverTimers) clearTimeout(timer);
    state.recoverTimers.clear();
    [0, 150, 500, 1200, 2500, 5000, 9000].forEach((delay) => {
      const timer = setTimeout(() => {
        state.recoverTimers.delete(timer);
        resumeAllPipelines();
        reconcileLiveSenders();
      }, delay);
      state.recoverTimers.add(timer);
    });
  }

  function reconcileLiveSenders() {
    if (!cfg().enabled) return;
    resumeAllPipelines();
    for (const pc of [...state.peerConnections]) {
      if (typeof pc.getSenders !== 'function') continue;
      try {
        for (const sender of pc.getSenders()) {
          const track = sender?.track;
          if (track?.kind === 'audio') rememberSender(sender, track);
        }
      } catch (_) {}
    }

    for (const record of [...state.senderRecords]) {
      if (recordIsClosed(record)) {
        state.senderRecords.delete(record);
        continue;
      }
      const sender = record.sender;
      const track = sender?.track || record.track;
      if (!sender || !track || track.kind !== 'audio') continue;
      if (trackNeedsRefresh(track)) queueSenderRefresh(sender, track);
      else {
        tuneAudioSender(sender);
        watchSenderTrack(sender, track);
      }
    }
  }

  function patchPeerConnectionPaths() {
    const PC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (PC?.prototype && !PC.prototype.__micMaxPcPatched) {
      const originalAddTrack = PC.prototype.addTrack;
      if (typeof originalAddTrack === 'function') {
        PC.prototype.addTrack = function addTrack(track, ...streams) {
          rememberPeerConnection(this);
          if (cfg().enabled && track?.kind === 'audio') {
            const processedTrack = processAudioTrack(track, true);
            const patchedStreams = streams.length
              ? streams.map((stream) => processedStreamFor(stream, track, processedTrack))
              : [new MediaStream([processedTrack])];
            const sender = originalAddTrack.call(this, processedTrack, ...patchedStreams);
            tuneAudioSender(sender);
            rememberSender(sender, processedTrack, this);
            if (typeof sender?.replaceTrack === 'function') watchSenderTrack(sender, processedTrack);
            return sender;
          }
          return originalAddTrack.call(this, track, ...streams);
        };
      }

      const originalAddTransceiver = PC.prototype.addTransceiver;
      if (typeof originalAddTransceiver === 'function') {
        PC.prototype.addTransceiver = function addTransceiver(trackOrKind, init = undefined) {
          rememberPeerConnection(this);
          if (cfg().enabled && trackOrKind?.kind === 'audio') {
            const processedTrack = processAudioTrack(trackOrKind, true);
            const patchedInit = init?.streams
              ? { ...init, streams: init.streams.map((stream) => processedStreamFor(stream, trackOrKind, processedTrack)) }
              : init;
            const transceiver = originalAddTransceiver.call(this, processedTrack, patchedInit);
            tuneAudioSender(transceiver?.sender);
            rememberSender(transceiver?.sender, processedTrack, this);
            if (typeof transceiver?.sender?.replaceTrack === 'function') watchSenderTrack(transceiver.sender, processedTrack);
            return transceiver;
          }
          return originalAddTransceiver.call(this, trackOrKind, init);
        };
      }
      PC.prototype.__micMaxPcPatched = true;
    }

    const Sender = window.RTCRtpSender;
    if (Sender?.prototype && !Sender.prototype.__micMaxSenderPatched) {
      const originalReplaceTrack = Sender.prototype.replaceTrack;
      if (typeof originalReplaceTrack === 'function') {
        Sender.prototype.replaceTrack = function replaceTrack(track) {
          const nextTrack = cfg().enabled && track?.kind === 'audio' ? processAudioTrack(track, true) : track;
          const result = originalReplaceTrack.call(this, nextTrack);
          if (nextTrack?.kind === 'audio') {
            rememberSender(this, nextTrack);
            Promise.resolve(result).then(() => {
              tuneAudioSender(this);
              watchSenderTrack(this, nextTrack);
            }).catch(() => {});
          }
          return result;
        };
      }
      Sender.prototype.__micMaxSenderPatched = true;
    }
  }

  async function getStreamWithFallback(orig, constraints, ctx) {
    try {
      return await orig.call(ctx, normalizeConstraints(constraints));
    } catch (_) {
      return orig.call(ctx, constraints);
    }
  }

  async function wrapped(orig, constraints, ctx) {
    if (wantsAudio(constraints)) state.lastAudioConstraints = constraints || { audio: true };
    const stream = await getStreamWithFallback(orig, constraints, ctx);
    if (!cfg().enabled || !wantsAudio(constraints)) return stream;
    try {
      return build(stream, state.config);
    } catch (_) {
      return stream;
    }
  }


  // ---- SHOYTANI SHOKTI: incoming call volume boost (only WebRTC call audio) ----
  const listen = { ctx: null, map: new WeakMap(), gains: new Set() };

  function listenCtx() {
    if (!listen.ctx || listen.ctx.state === 'closed') {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return null;
      listen.ctx = new AC({ latencyHint: 'interactive' });
    }
    if (listen.ctx.state === 'suspended') listen.ctx.resume().catch(() => {});
    return listen.ctx;
  }

  function isCallMedia(el) {
    // Only live MediaStream elements (calls). Normal chat videos/audio are never touched,
    // because hooking cross-origin media would silence them.
    if (!el || (el.tagName !== 'AUDIO' && el.tagName !== 'VIDEO')) return false;
    if (typeof MediaStream !== 'undefined' && el.srcObject instanceof MediaStream && el.srcObject.getAudioTracks().length > 0) return true;
    // v8: same-origin voice messages (blob:) in WhatsApp/Telegram/Instagram
    return el.tagName === 'AUDIO' && /^blob:/.test(el.currentSrc || el.src || '');
  }

  function bq(c, type, freq, q, gain) {
    const f = c.createBiquadFilter();
    f.type = type;
    f.frequency.value = freq;
    if (q != null) f.Q.value = q;
    if (gain != null) f.gain.value = gain;
    return f;
  }

  function applyChain(chain, c) {
    const on = c.enabled;
    const t = on ? c.listenGain : 1;
    const clar = on ? c.listenClarity : 0;
    const bass = on ? c.listenBass : 0;
    try {
      chain.g.gain.value = t;
      chain.bass.gain.value = bass;
      chain.clar.gain.value = clar * 1.5;
      chain.air.gain.value = clar * 0.6;
      chain.mud.gain.value = -(clar * 0.5);
      chain.vocal.gain.value = clar * 0.9;
      chain.punch.gain.value = on ? c.listenPunch * 2.5 : 1;
    } catch (_) {}
  }

  function hookCallMedia(el) {
    if (listen.map.has(el) || !isCallMedia(el)) return;
    try {
      const c = listenCtx();
      if (!c) return;
      const src = c.createMediaElementSource(el);
      const g = c.createGain();
      // v10: studio voice chain. rumble + hiss cut, mud cut, two speech-presence bands,
      // two-stage leveling, loudness punch, brick-style limiter, soft ceiling.
      const hp = bq(c, 'highpass', 85, 0.7);
      const lp = bq(c, 'lowpass', 9500, 0.6);
      const mud = bq(c, 'peaking', 320, 1.0, -3);
      const bass = bq(c, 'lowshelf', 160, null, 4);
      const vocal = bq(c, 'peaking', 1600, 1.0, 4);
      const clar = bq(c, 'peaking', 2900, 1.1, 8);
      const air = bq(c, 'highshelf', 6000, null, 4);
      const lev = c.createDynamicsCompressor();
      lev.threshold.value = -46;
      lev.knee.value = 20;
      lev.ratio.value = 10;
      lev.attack.value = 0.003;
      lev.release.value = 0.14;
      const makeup = c.createGain();
      makeup.gain.value = 12;
      const lev2 = c.createDynamicsCompressor();
      lev2.threshold.value = -26;
      lev2.knee.value = 8;
      lev2.ratio.value = 6;
      lev2.attack.value = 0.002;
      lev2.release.value = 0.09;
      const punch = c.createGain();
      punch.gain.value = 3;
      const lim = c.createDynamicsCompressor();
      lim.threshold.value = -1;
      lim.knee.value = 0;
      lim.ratio.value = 20;
      lim.attack.value = 0.0005;
      lim.release.value = 0.05;
      const soft = c.createWaveShaper();
      soft.curve = makeSoftClipCurve(4.5);
      soft.oversample = '4x';
      const out = c.createGain();
      out.gain.value = 1;
      src.connect(g);
      g.connect(hp);
      hp.connect(lp);
      lp.connect(mud);
      mud.connect(bass);
      bass.connect(vocal);
      vocal.connect(clar);
      clar.connect(air);
      air.connect(lev);
      lev.connect(makeup);
      makeup.connect(lev2);
      lev2.connect(punch);
      punch.connect(lim);
      lim.connect(soft);
      soft.connect(out);
      out.connect(c.destination);
      const chain = { g, bass, clar, air, mud, vocal, punch };
      applyChain(chain, cfg());
      listen.map.set(el, chain);
      listen.gains.add(chain);
    } catch (_) {}
  }

  function applyListen() {
    const c = cfg();
    if (c.enabled && c.listenGain > 1) {
      try { document.querySelectorAll('audio,video').forEach(hookCallMedia); } catch (_) {}
      if (listen.ctx && listen.ctx.state === 'suspended') listen.ctx.resume().catch(() => {});
    }
    listen.gains.forEach((chain) => applyChain(chain, c));
  }

  const origMediaPlay = window.HTMLMediaElement?.prototype?.play;
  if (origMediaPlay && !window.HTMLMediaElement.prototype.__dmPlayPatched) {
    window.HTMLMediaElement.prototype.play = function play() {
      try { if (cfg().enabled && cfg().listenGain > 1) hookCallMedia(this); } catch (_) {}
      return origMediaPlay.apply(this, arguments);
    };
    window.HTMLMediaElement.prototype.__dmPlayPatched = true;
  }


  patchPeerConnectionPaths();
  patchTrackConstraints();

  if (navigator.mediaDevices?.getUserMedia) {
    state.origMD = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = (constraints) => wrapped(state.origMD, constraints, navigator.mediaDevices);
  }

  if (navigator.getUserMedia) {
    state.origLegacy = navigator.getUserMedia.bind(navigator);
    navigator.getUserMedia = (constraints, ok, fail) => {
      wrapped(state.origLegacy, constraints, navigator).then(ok).catch((err) => fail && fail(err));
    };
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window || !event.data || event.data.type !== MSG_CFG) return;
    state.config = cfg(event.data.payload);
    updateAllPipelines(state.config);
    applyListen();
    scheduleRecoveryPasses();
  });

  ['focus', 'pageshow', 'online', 'pointerdown', 'touchstart'].forEach((type) => {
    window.addEventListener(type, scheduleRecoveryPasses, { passive: true });
  });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) scheduleRecoveryPasses();
  });

  setInterval(() => {
    enforceAllSourceConstraints();
    resumeAllPipelines();
    reconcileLiveSenders();
    applyListen();
  }, 1000);
  window.postMessage({ type: 'MIC_MAXIMIZER_READY' }, '*');
})();
