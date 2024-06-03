window.onload = () => {
  let dotIdx = 0;
  let dotInterval;

  const status = document.getElementById("status");
  const error = document.getElementById("error");
  const dotdotdot = document.getElementById("dotdotdot");

  const initBlock = document.getElementById("initBlock");
  const chatBlock = document.getElementById("chatBlock");

  const initButton = document.getElementById("init");
  const resetDialogButton = document.getElementById("resetDialog");

  const accessKey = document.getElementById("accessKey");
  const modelFile = document.getElementById("uploadFile");
  const message = document.getElementById("message");
  const result = document.getElementById("result");

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

  const startHumanMessage = () => {
    result.innerHTML += `<div class="align-end"><span class="human-border">You</span></div>`;

    const divElem = document.createElement("div");
    divElem.className = "align-end";

    const textElem = document.createElement("span");
    textElem.className = "human-text";
    textElem.innerHTML = "";

    divElem.appendChild(textElem);
    result.appendChild(divElem);

    result.scrollTop = result.scrollHeight;

    return textElem;
  }

  const startLLMMessage = () => {
    result.innerHTML += `<div class="align-start"><span class="llm-border">picoLLM</span></div>`;

    const divElem = document.createElement("div");
    divElem.className = "align-start";

    const textElem = document.createElement("span");
    textElem.className = "llm-text";
    textElem.innerHTML = "";

    divElem.appendChild(textElem);
    result.appendChild(divElem);

    result.scrollTop = result.scrollHeight;

    return textElem;
  }

  const addMessage = (elem, messageString) => {
    elem.innerHTML += messageString;
    result.scrollTop = result.scrollHeight;
  };

  initButton.onclick = async () => {
    initButton.disabled = true;
    status.innerText = "Loading model"
    startDot();

    let humanElem, llmElem;
    let completeTranscript = "";
    let audioStream;
    let streamCalls = 0;

    try {
      await Picovoice.init(accessKey.value,
        {
          modelFile: modelFile.files[0],
          cacheFilePath: modelFile.files[0].name,
        },
        {
        onDetection: () => {
          message.innerText = "Wake word detected, utter your request or question...";
          humanElem = startHumanMessage();
        },
        onTranscript: (transcript) => {
          completeTranscript += transcript;
          addMessage(humanElem, transcript);
        },
        onEndpoint: async () => {
          message.innerText = "Generating...";
          llmElem = startLLMMessage();
        },
        onText: (text) => {
          addMessage(llmElem, text);
        },
        onStream: async (pcm) => {
          audioStream.stream(pcm);
          if (streamCalls > 1) {
            audioStream.play();
          } else {
            streamCalls++;
          }
        },
        onComplete: async () => {
          if (streamCalls <= 2) {
            audioStream.play();
          }
          await audioStream.waitPlayback();

          await Picovoice.start();
          message.innerText = "Say `Picovoice`"
          streamCalls = 0;
        },
      });
      initBlock.style.display = 'none';
      chatBlock.style.display = 'flex';
      status.innerText = "Loading complete."

      await Picovoice.start();
      message.innerText = "Say `Picovoice`"

      if (!audioStream) {
        audioStream = new Picovoice.AudioStream(Picovoice.getStreamSampleRate());
      }
    } catch (e) {
      writeError(e.message);
    } finally {
      stopDot();
    }
  };

  accessKey.onchange = () => {
    if (accessKey.value.length > 0 && modelFile.files.length > 0) {
      initButton.disabled = false;
    }
  }

  modelFile.onchange = accessKey.onchange;

  resetDialogButton.onclick = () => {
    result.innerHTML = '';
  }
};
