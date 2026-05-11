window.onload = () => {
  const ANIMATION_FRAMES = ["   ", ".  ", ".. ", "...", " ..", "  ."];

  let dotIdx = 0;
  let dotInterval = null;

  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const errorContainer = document.getElementById("error-container");
  const error = document.getElementById("error");
  const dotdotdot = document.getElementById("dotdotdot");

  const initBlock = document.getElementById("initBlock");
  const chatBlock = document.getElementById("chatBlock");

  const accessKey = document.getElementById("accessKey");
  const name = document.getElementById("name");
  const initButton = document.getElementById("init");
  const initButtonTooltip = document.getElementById("initButtonTooltip");
  const sourceLanguage = document.getElementById("sourceLanguage");
  const targetLanguage = document.getElementById("targetLanguage");
  const message = document.getElementById("message");
  const result = document.getElementById("result");

  const writeError = (errorString) => {
    errorContainer.style.display = 'block';
    error.style.display = 'block';
    error.innerText = `Error: ${errorString}`;
    writeStatus("Error");
  };

  const writeStatus = (statusString) => {
    statusContainer.style.display = 'block';
    status.style.display = 'inline';
    status.innerText = statusString;
  };

  const enableInitButton = _ => {
    let isValid = (accessKey.value.length > 0) && (name.value.length > 0);
    if (isValid) {
      initButton.removeAttribute("disabled");
      initButton.setAttribute("coloured", "");
      initButtonTooltip.innerHTML = "";
    } else {
      initButton.setAttribute("disabled", "");
      initButton.removeAttribute("coloured");
      initButtonTooltip.innerHTML = "fill in access key and name";
    }
  }

  accessKey.addEventListener("input", enableInitButton);
  name.addEventListener("input", enableInitButton);

  //let animationIndex = 0;
  //const animationElement = document.createElement("span");
  /*const animationInterval = setInterval(() => {
    const frame = ANIMATION_FRAMES[animationIndex];
    animationElement.innerText = ` ${frame} `;
    animationIndex = (animationIndex + 1) % ANIMATION_FRAMES.length;
  }, 500);
  const state = {
    bubbleElem: null,
    upperElem: null,
    lowerElem: null,
    upperTextElem: null,
    lowerTextElem: null,
  };*/

  const startDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % ANIMATION_FRAMES.length;
      dotdotdot.innerText = ANIMATION_FRAMES[dotIdx];
    }, 100);
  }

  const stopDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotIdx = 0;
    dotdotdot.innerText = '';
  }

  const sendMessage = (message, text) => {
    // result.scrollTop = result.scrollHeight;

    if (message === "status") {
      writeStatus(text);
    } else if (message === "add to bubble") {
      chatBlock.lastElementChild.innerHTML += text;
      
    } else if (message === "new caller bubble") {
      let bubble = document.createElement("div");
      bubble.classList.add("caller-bubble");
      chatBlock.appendChild(bubble);

    } else if (message === "new ai bubble") {
      let bubble = document.createElement("div");
      bubble.classList.add("ai-bubble");
      chatBlock.appendChild(bubble);
  
    } /*else if (mode === "prompt") {
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
    } else if (mode === "detecting") {
      initBlock.style.display = 'none';
      chatBlock.style.display = 'flex';
    } */ else if (message === "loading") {
      /*
      if (state.upperTextElem) {
        state.upperTextElem.innerText = "";
      }*/
    }
  };

  initButtonTooltip.innerHTML = "fill in access key and name";

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    initButtonTooltip.innerHTML = "fix error first";
    return;
  }

  initButton.addEventListener("click", async () => {
    initButton.disabled = true;
    writeStatus("Loading");
    startDot();

    try {
      const start = await Picovoice.init(
        accessKey.value,
        sendMessage
      );

      if (start !== null) {
        writeStatus("Loading complete.");
        await start();
      }
    } catch (e) {
      writeError(e.message);
    } finally {
      stopDot();
    }
  });

  sourceLanguage.onchange = () => {
    Array.from(targetLanguage.options).forEach((option) => {
      option.disabled = !Picovoice.LANGUAGE_PAIRS[sourceLanguage.value].includes(option.value);

      if ((targetLanguage.value === option.value) && (option.disabled === true)) {
        targetLanguage.value = Picovoice.LANGUAGE_PAIRS[sourceLanguage.value][0];
      }
    });
  }
};
