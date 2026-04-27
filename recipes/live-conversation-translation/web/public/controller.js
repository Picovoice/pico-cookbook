window.onload = () => {
  let dotIdx = 0;
  let dotInterval = null;

  const status = document.getElementById("status");
  const error = document.getElementById("error");
  const dotdotdot = document.getElementById("dotdotdot");

  const initBlock = document.getElementById("initBlock");
  const chatBlock = document.getElementById("chatBlock");

  const initButton = document.getElementById("init");

  const accessKey = document.getElementById("accessKey");
  const sourceLanguage = document.getElementById("sourceLanguage");
  const targetLanguage = document.getElementById("targetLanguage");
  const changeLanguageButton = document.getElementById("changeLanguageButton");
  const message = document.getElementById("message");
  const result = document.getElementById("result");

  let animationIndex = 0;
  const animationElement = document.createElement("span");
  const animationFrames = ["   ", ".  ", ".. ", "...", " ..", "  ."];
  const animationInterval = setInterval(() => {
    const frame = animationFrames[animationIndex];
    animationElement.innerText = ` ${frame} `;
    animationIndex = (animationIndex + 1) % animationFrames.length;
  }, 500);

  const state = {
    bubbleElem: null,
    upperElem: null,
    lowerElem: null,
    upperTextElem: null,
    lowerTextElem: null,
  };

  const writeError = (errorString) => {
    error.style.display = 'block';
    error.innerText = `Error: ${errorString}`;
    status.innerText = 'Error.';
  };

  const startDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % 4;
      let dotText = '';
      for (let i = 0; i < dotIdx; i++) {
        dotText += '.';
      }
      dotdotdot.innerText = dotText;
    }, 500);
  }

  const stopDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotIdx = 0;
    dotdotdot.innerText = '';
  }

  const sendState = (mode, text) => {
    result.scrollTop = result.scrollHeight;

    if (mode === "status") {
      status.innerText = text;
    } else if (mode === "prompt") {
      message.innerText = text;
    } else if (mode === "listen") {
      const rowElem = document.createElement("div");
      const bubbleElem = document.createElement("div");
      const upperElem = document.createElement("div");
      const upperTextElem = document.createElement("span");

      if (text === "right") {
        rowElem.className = "align-end";
      } else if (text === "left") {
        rowElem.className = "align-start";
      }

      bubbleElem.className = "text-bubble";
      upperElem.classList = "upper-bubble";

      upperElem.appendChild(upperTextElem);
      upperElem.appendChild(animationElement);
      bubbleElem.appendChild(upperElem);
      rowElem.appendChild(bubbleElem);
      result.appendChild(rowElem);

      result.scrollTop = result.scrollHeight;
      state.bubbleElem = bubbleElem
      state.upperElem = upperElem;
      state.upperTextElem = upperTextElem;
    } else if (mode === "transcript") {
      state.upperTextElem.innerText += text;
    } else if (mode === "translate") {
      const lowerElem = document.createElement("div");
      const lowerTextElem = document.createElement("span");
      lowerElem.classList = "lower-bubble";
      lowerElem.appendChild(lowerTextElem);
      state.bubbleElem.appendChild(lowerElem);

      state.lowerElem = lowerElem;
      state.lowerTextElem = lowerTextElem;

      state.upperElem.classList.add("mute-text");
      state.upperElem.removeChild(animationElement);
      state.lowerElem.appendChild(animationElement);
    } else if (mode === "translation") {
      state.lowerTextElem.innerText += text;
      if (text == "") {
        state.lowerElem.removeChild(animationElement);
      }
    }
  };

  initButton.onclick = async () => {
    initButton.disabled = true;
    status.innerText = "Loading"
    startDot();

    try {
      const source = sourceLanguage.value;
      const target = targetLanguage.value;

      const start = await Picovoice.init(
        accessKey.value,
        source,
        target,
        sendState
      );
      initBlock.style.display = 'none';
      chatBlock.style.display = 'flex';
      status.innerText = "Loading complete.";

      await start();
      changeLanguageButton.disabled = false;
    } catch (e) {
      writeError(e.message);
    } finally {
      stopDot();
    }
  };

  changeLanguageButton.onclick = async () => {
    await Picovoice.release();

    result.innerHTML = "";

    initBlock.style.display = 'block';
    chatBlock.style.display = 'none';

    initButton.disabled = false;
    status.innerText = "Not loaded."

  };

  const enableInitButton = () => {
    if ((accessKey.value.length > 0)) {
      initButton.disabled = false;
    } else {
      initButton.disabled = true;
    }
  }

  accessKey.onchange = () => {
    enableInitButton();
  }

  sourceLanguage.onchange = () => {
    Array.from(targetLanguage.options).forEach((option) => {
      option.disabled = !Picovoice.LANGUAGE_PAIRS[sourceLanguage.value].includes(option.value);

      if ((targetLanguage.value === option.value) && (option.disabled === true)) {
        targetLanguage.value = Picovoice.LANGUAGE_PAIRS[sourceLanguage.value][0];
      }
    });
  }
};