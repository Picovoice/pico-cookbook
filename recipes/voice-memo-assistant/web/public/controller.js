window.onload = () => {
  const accessKey = document.getElementById('accessKey');
  const initButton = document.getElementById('init');

  const topStatusBlock = document.getElementById('topStatusBlock');
  const topStatus = document.getElementById('topStatus');
  const tooltip = document.getElementById('tooltip');
  const originalText = document.getElementById('originalText');
  const modifiedText = document.getElementById('modifiedText');
  const modifiedTitle = document.getElementById('modifiedTitle');

  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  const updateUI = state => {
    switch (state) {
      case 'WAKE_WORD':
        tooltip.innerText = 'Listening for the wake word...';
        meterContainer.style.display = 'flex';
        break;
      case 'VOICE_COMMAND':
        if (originalText.innerText.length > 0) {
          tooltip.innerText =
            "Commands: 'start memo', 'read memo', 'summarize memo', 'rewrite memo'";
        } else {
          tooltip.innerText = "Say 'start memo' to record a voice memo"
        }
        meterContainer.style.display = 'flex';
        break;
      case 'START_RECORDING':
        tooltip.innerText = "Say 'stop recording' to end.";
        meterContainer.style.display = 'flex';

        modifiedContainer.style.display = 'none';
        modifiedText.innerText = '';
        break;
      case 'READ_RECORDING':
        if (originalText.innerText.length > 0) {
          tooltip.innerText = 'Reading memo aloud...';
        } else {
          tooltip.innerText = "Record a memo with 'start memo' first";
        }
        meterContainer.style.display = 'none';
        break;
      case 'SUMMARIZE_RECORDING':
        tooltip.innerText = 'Generating summary...';
        modifiedTitle.innerText = 'Summarized:';
        meterContainer.style.display = 'none';

        modifiedContainer.style.display = 'block';
        modifiedText.innerText = '';
        break;
      case 'REWRITE_RECORDING':
        tooltip.innerText = 'Generating rewrite...';
        modifiedTitle.innerText = 'Rewritten:';
        meterContainer.style.display = 'none';

        modifiedContainer.style.display = 'block';
        modifiedText.innerText = '';
        break;
    }
  };

  initButton.onclick = async () => {
    initButton.disabled = true;
    topStatus.innerText = 'Loading engines...';

    try {
      await Picovoice.init(accessKey.value, {
        onStateChange: state => updateUI(state),
        onOriginalText: text => (originalText.innerText = text),
        onModifiedText: text => (modifiedText.innerText = text),
        onVolume: volume => {
          const baseHeight = 8;
          bar1.style.height = `${baseHeight + volume * 20}px`;
          bar2.style.height = `${baseHeight + volume * 32}px`;
          bar3.style.height = `${baseHeight + volume * 24}px`;
        },
        onError: err => alert(err),
      });

      topStatusBlock.style.display = 'none';
      document.getElementById('initBlock').style.display = 'none';
      document.getElementById('appBlock').style.display = 'flex';
    } catch (e) {
      topStatus.innerText = 'Error loading engines: ' + e.message;
      initButton.disabled = false;
    }

    await Picovoice.start();
  };

  accessKey.onchange = () => {
    if (accessKey.value) initButton.disabled = false;
  };
};
