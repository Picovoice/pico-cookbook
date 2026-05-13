window.onload = () => {
  const accessKey = document.getElementById('accessKey');
  const initButton = document.getElementById('init');
  const resetButton = document.getElementById('reset');

  const error = document.getElementById("error");

  const fileSelector = document.getElementById('audioFile');
  const audioFileClear = document.getElementById('audioFileClear');
  const sourceLanguage = document.getElementById('sourceLanguage');
  const targetLanguage = document.getElementById('targetLanguage');

  const topStatusBlock = document.getElementById('topStatusBlock');
  const topStatus = document.getElementById('topStatus');

  const result = document.getElementById("result");

  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  const state = {
    bubbleElem: null,
    upperElem: null,
    upperTextElem: null,
  };

  const writeError = (errorString) => {
    error.style.display = 'block';
    error.innerText = `Error: ${errorString}`;
    status.innerText = 'Error.';
  };

  const updateUI = uiState => {
    switch (uiState) {
      case 'INIT':
        topStatusBlock.style.display = 'block';
        document.getElementById('initBlock').style.display = 'block';
        document.getElementById('appBlock').style.display = 'none';
        document.getElementById('meterContainer').style.display = 'none';
        break;
    }
  };

  const onOriginalText = transcript => {
    state.upperTextElem.innerText = transcript.trim() + " ";
  }

  const onModifiedText = transcriptLines => {
    const line = transcriptLines[transcriptLines.length - 1]

    if (line.translated !== null) {
      const lowerElem = document.createElement("div");
      const lowerTextElem = document.createElement("span");

      lowerElem.classList = "lower-bubble";
      lowerTextElem.innerText = line.translated;

      lowerElem.appendChild(lowerTextElem);
      state.bubbleElem.appendChild(lowerElem);
    }

    state.upperElem.classList.add("mute-text");
    state.upperTextElem.innerText = line.caption;

    createNewTextBubble();

    result.scrollTop = result.scrollHeight;
  }

  const createNewTextBubble = () => {
    const rowElem = document.createElement("div");
    const bubbleElem = document.createElement("div");

    const upperElem = document.createElement("div");
    const upperTextElem = document.createElement("span");

    rowElem.className = "align-start";

    bubbleElem.className = "text-bubble";
    upperElem.classList = "upper-bubble";

    upperElem.appendChild(upperTextElem);
    bubbleElem.appendChild(upperElem);

    rowElem.appendChild(bubbleElem);
    result.appendChild(rowElem);

    upperTextElem.innerText = " "

    state.bubbleElem = bubbleElem;
    state.upperElem = upperElem;
    state.upperTextElem = upperTextElem;
  }

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    return;
  }

  initButton.onclick = async () => {
    initButton.disabled = true;
    topStatus.innerText = 'Loading engines...';

    const selectedFile = fileSelector.files[0];

    try {
      await Picovoice.init(
        accessKey.value,
        sourceLanguage.value,
        targetLanguage.value,
        {
          onStateChange: uiState => updateUI(uiState),
          onOriginalText: onOriginalText,
          onModifiedText: onModifiedText,
          onVolume: volume => {
            const baseHeight = 8;
            bar1.style.height = `${baseHeight + volume * 20}px`;
            bar2.style.height = `${baseHeight + volume * 32}px`;
            bar3.style.height = `${baseHeight + volume * 24}px`;
          },
          onError: err => alert(err),
        }
      );

      topStatusBlock.style.display = 'none';
      document.getElementById('initBlock').style.display = 'none';
      document.getElementById('appBlock').style.display = 'flex';

      if (!selectedFile) {
        document.getElementById('meterContainer').style.display = 'flex';
      }

      createNewTextBubble();
    } catch (e) {
      topStatus.innerText = 'Error loading engines: ' + e.message;
      initButton.disabled = false;
    }

    await Picovoice.start(selectedFile);
  };

  if (accessKey.value) initButton.disabled = false;
  accessKey.onchange = () => {
    if (accessKey.value) initButton.disabled = false;
  };

  audioFileClear.onclick = () => {
    fileSelector.value = null;
  }

  sourceLanguage.value = 'en'
  targetLanguage.value = 'en'
  sourceLanguage.onchange = () => {
    Array.from(targetLanguage.options).forEach((option) => {
      option.disabled = !Picovoice.LANGUAGE_PAIRS[sourceLanguage.value].includes(option.value);

      if ((targetLanguage.value === option.value) && (option.disabled === true)) {
        targetLanguage.value = sourceLanguage.value;
      }
    });
  }

  resetButton.onclick = async () => {
    await Picovoice.release();

    topStatus.innerText = 'Not Loaded.';
    if (accessKey.value) initButton.disabled = false;
    result.replaceChildren();

    state.bubbleElem = null;
    state.upperElem = null;
    state.upperTextElem = null;
  }
};
