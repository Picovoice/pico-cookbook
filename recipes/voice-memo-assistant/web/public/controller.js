window.onload = () => {
  const accessKey = document.getElementById("accessKey");
  const initButton = document.getElementById("init");

  const status = document.getElementById("status");
  const tooltip = document.getElementById("tooltip");
  const originalText = document.getElementById("originalText");
  const modifiedText = document.getElementById("modifiedText");
  const modifiedTitle = document.getElementById("modifiedTitle");

  const volumeMeter = document.getElementById("volumeMeter");
  const bar1 = document.getElementById("bar1");
  const bar2 = document.getElementById("bar2");
  const bar3 = document.getElementById("bar3");

  let audioStream;

  const updateUI = (state) => {
    switch(state) {
      case 2: // WAKE_WORD
        status.innerText = "Say 'Picovoice' to wake up!";
        tooltip.innerText = "";
        volumeMeter.classList.remove('hidden');
        break;
      case 3: // VOICE_COMMAND
        status.innerText = "Listening for command...";
        tooltip.innerText = "Say 'start memo', 'read memo', 'summarize memo', or 'rewrite memo'";
        volumeMeter.classList.remove('hidden');
        break;
      case 4: // START_RECORDING
        status.innerText = "Recording Memo...";
        tooltip.innerText = "Say 'stop recording' to end.";
        volumeMeter.classList.remove('hidden');

        modifiedContainer.style.display = "none";
        modifiedText.innerText = "";
        break;
      case 5: // READ_RECORDING
        status.innerText = "Speaking...";
        tooltip.innerText = "";
        volumeMeter.classList.add('hidden');
        break;
      case 6: // SUMMARIZE_RECORDING
        status.innerText = "Summarizing via PicoLLM...";
        modifiedTitle.innerText = "Summarized:";
        volumeMeter.classList.add('hidden');

        modifiedContainer.style.display = "block";
        modifiedText.innerText = "";
        break;
      case 7: // REWRITE_RECORDING
        status.innerText = "Rewriting via PicoLLM...";
        modifiedTitle.innerText = "Rewritten:";
        volumeMeter.classList.add('hidden');

        modifiedContainer.style.display = "block";
        modifiedText.innerText = "";
        break;
    }
  };

  initButton.onclick = async () => {
    initButton.disabled = true;
    status.innerText = "Loading engines...";

    try {
      await Picovoice.init(accessKey.value, {
        onStateChange: (state) => updateUI(state),
        onOriginalText: (text) => originalText.innerText = text,
        onModifiedText: (text) => modifiedText.innerText = text,
        onVolume: (volume) => {
          const baseHeight = 8;
          bar1.style.height = `${baseHeight + volume * 20}px`;
          bar2.style.height = `${baseHeight + volume * 32}px`;
          bar3.style.height = `${baseHeight + volume * 24}px`;
        },
        onAudioReady: async (pcm) => {
          audioStream.stream(pcm);
          audioStream.play();
          await audioStream.waitPlayback();
        },
        onError: (err) => alert("Error: " + err)
      });

      document.getElementById("initBlock").style.display = 'none';
      document.getElementById("appBlock").style.display = 'block';

      await Picovoice.start();
      audioStream = new Picovoice.AudioStream(Picovoice.getStreamSampleRate());

    } catch (e) {
      alert(e.message);
    }
  };

  accessKey.onchange = () => {
    if (accessKey.value) initButton.disabled = false;
  };
};