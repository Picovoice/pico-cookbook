window.onload = () => {
  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');
  const initContainer = document.getElementById('initContainer');
  const appContainer = document.getElementById('appContainer');

  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const statusSpinner = document.getElementById("status-spinner");

  const titleText = document.getElementById('titleText');
  const noticeText = document.getElementById('notice-text');
  const extraText = document.getElementById('extra-text');

  const volumeMeter = document.getElementById('volumeMeter');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  const buttonContainer = document.getElementById('buttonContainer');
  const btnStartEnroll = document.getElementById('btnStartEnroll');
  const btnStartTest = document.getElementById('btnStartTest');
  const btnClearAll = document.getElementById('btnClearAll');
  const btnCancel = document.getElementById('btnCancel');

  // TODO: The UI should have two states & be as simple as possible.

  let currentState = 'IDLE';

  function showError(message) {
    errorText.innerText = message;
    errorText.classList.remove('hidden');
  }

  function clearError() {
    errorText.innerText = '';
    errorText.classList.add('hidden');
  }

  if (typeof Picovoice === 'undefined') {
    showError('You must run `yarn build` before running `yarn start`');
    btnInit.disabled = true;
    return;
  }

  const callbacks = {
    onUpdateStatus: (statusString) => {
      statusContainer.style.display = 'flex';
      status.innerHTML = statusString;

      if (status.length == 0) {
        statusContainer.style.display = 'none';
      }
    },
    setLoadingState: async (enabled) => {
      if (enabled) {
        statusSpinner.style.display = "inline";
        statusSpinner.style.opacity = "1";
      } else {
        statusSpinner.style.opacity = "0";
        
        await Picovoice.sleep(200);
        
        statusSpinner.style.display = "none";
      }
    },
    onVolume: volume => {
      const baseHeight = 8;
      bar1.style.height = `${baseHeight + volume * 20}px`;
      bar2.style.height = `${baseHeight + volume * 32}px`;
      bar3.style.height = `${baseHeight + volume * 24}px`;
    },
    onEnrollProgress: progress => {
      enrollProgress.value = progress;
    },
    onEnrollComplete: profile => {
      speakerProfiles.push(profile);
      speakerNames.push(pendingSpeakerName);
      speakerRoles.push(pendingSpeakerRole);
      Picovoice.stopEnrollment();

      updateUIForState('IDLE');
    },
    onWakeWordRecognized: async () => {
      noticeText.innerHTML = "Speak Command";

      hudOptions.style.opacity = '0';
      hudOptions.replaceChildren();

      const createOption = (text, subtext) => {
        let optionContainer = document.createElement("div");

        let option = document.createElement("div");
        option.innerHTML = text;
        option.className = "label";

        let tooltip = document.createElement("span");
        tooltip.classList.add("tooltip");
        tooltip.innerHTML = "To select this voice command, speak into your microphone"

        option.appendChild(tooltip);
        
        let subtextObject = document.createElement("div");
        subtextObject.innerHTML = subtext;
        subtextObject.className = "subtext";

        optionContainer.appendChild(option);
        optionContainer.appendChild(subtextObject);

        hudOptions.appendChild(optionContainer);
      };

      createOption("Do something that requires admin permission", "Will only work if you are an admin");
      createOption("Do something just for me", "Will trigger a personalized response");
      createOption("Do something anyone can do", "Will trigger a generic response");

      hudOptions.style.display = 'flex';
      await Picovoice.sleep(100);

      hudOptions.style.opacity = '1';
    },
    beforeInferenceResponse: (_intent, _similarityScore) => {
      noticeText.innerHTML = "&nbsp;";
    },
    onWordSpoken: (word) => {
      if (noticeText.innerHTML === "&nbsp;") {
        noticeText.innerHTML = word;
      } else {
        noticeText.innerHTML += word;
      }
    },
    afterInferenceResponse: async (success) => {
      hudOptions.style.opacity = '0';

      await Picovoice.sleep(400);
      hudOptions.style.display = 'none';

      if (success) {
        noticeText.innerText = "Say the wakeword";
      }
    },
    onError: err => {
      showError(err);
    },
  };

  btnInit.onclick = async () => {
    clearError();
    const accessKey = accessKeyInput.value.trim();
    if (!accessKey) {
      showError('Please enter an AccessKey');
      return;
    }

    btnInit.disabled = true;
    btnInit.innerText = 'Initializing...';

    try {
      await Picovoice.init(accessKey, callbacks);
      initContainer.classList.add('hidden');
      appContainer.classList.remove('hidden');
      promptForSpeakerName();
    } catch (e) {
      btnInit.disabled = false;
      btnInit.innerText = 'Start Enrollment';
    }
  };

  btnStartEnroll.onclick = promptForSpeakerName;

  btnStartTest.onclick = async () => {
    const zippedProfiles = speakerProfiles.map((p, i) => ({
      profile: p,
      name: speakerNames[i],
      role: speakerRoles[i],
    }));
  
    await Picovoice.startTesting(zippedProfiles);
    updateUIForState('TESTING');
  };

  btnClearAll.onclick = () => {
    speakerProfiles = [];
    speakerNames = [];
    updateUIForState('IDLE');
  };

  btnCancel.onclick = async () => {
    if (currentState === 'ENROLLING') {
      await Picovoice.stopEnrollment();
    } else {
      await Picovoice.stopTesting();
    }

    updateUIForState('IDLE');
  };

  function updateUIForState(newState) {
    currentState = newState;
    const hasProfiles = speakerProfiles.length > 0;

    if (currentState === 'ENROLLING') {
      noticeText.innerHTML = `<div style="display: flex;">
                                <span>Hello ${pendingSpeakerName},</span>
                                <div style="margin-left: auto; display: inline-block;">Role: <b>${pendingSpeakerRole}</b></div>
                              </div>\n` +
                             "<span style='font-size: 14px;'>Read the following sentences aloud in your normal voice.<br>" +
                             "Keep speaking until enrollment reaches 100%.</span>";
      noticeText.style.textAlign = 'start';
      noticeText.classList.remove('hidden');
      extraText.classList.remove('hidden');
      extraText.innerHTML = "1. The quick brown fox jumps over the lazy dog.\n" +
                            "2. I am recording my voice for speaker enrollment.\n" +
                            "3. This is my normal speaking voice in a quiet room.\n" +
                            "4. The assistant should recognize me when I speak.\n" +
                            "5. Voice recognition works best with clean and natural speech.";
      enrollProgress.classList.remove('hidden');
      enrollProgress.value = 0;

      titleText.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Cancel';
      btnCancel.classList.remove('hidden');
      btnClearAll.classList.add('hidden');
    } else if (currentState === 'TESTING') {
      noticeText.innerText = "Say the wakeword";
      noticeText.style.textAlign = 'center';
      noticeText.classList.remove('hidden');
      extraText.classList.add('hidden');
      enrollProgress.classList.add('hidden');

      hudOptions.style.opacity = '0';
      hudOptions.style.display = 'none';

      titleText.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Stop Testing';
      btnCancel.classList.remove('hidden');
      btnClearAll.classList.add('hidden');
    } else {
      noticeText.classList.toggle('hidden', hasProfiles);
      noticeText.innerText = 'Ready to Enroll';
      noticeText.style.textAlign = 'center';
      extraText.classList.add('hidden');

      hudOptions.style.opacity = '0';
      hudOptions.style.display = 'none';

      enrollProgress.classList.add('hidden');
      volumeMeter.classList.add('hidden');
      btnCancel.classList.add('hidden');

      titleText.classList.remove('hidden');
      buttonContainer.classList.remove('hidden');

      if (hasProfiles) {
        layoutDashboard.classList.remove('hidden');
        renderSpeakerChips();

        btnStartEnroll.classList.add('hidden');
        btnStartTest.classList.remove('hidden');
        btnClearAll.classList.remove('hidden');
      } else {
        layoutDashboard.classList.add('hidden');

        btnStartEnroll.classList.remove('hidden');
        btnStartTest.classList.add('hidden');
        btnClearAll.classList.add('hidden');
      }
    }
  }

  function renderSpeakerChips() {
    chipContainer.innerHTML = '';
  
    for (let i = 0; i < speakerNames.length; i++) {
      const chip = document.createElement('div');
      chip.className = 'speaker-chip';
      chip.innerText = speakerNames[i];
      chip.style.backgroundColor = SPEAKER_PALETTE[i];
      chip.addEventListener("click", () => {
        speakerNames.splice(i, 1);
        renderSpeakerChips();
      });

      const deleteCross = document.createElement('div');
      deleteCross.className = "delete-cross";
      deleteCross.innerHTML = "✕";
      chip.appendChild(deleteCross);
      
      chipContainer.appendChild(chip);
    }
  
    if (speakerProfiles.length < MAX_SPEAKERS) {
      const addBtn = document.createElement('div');
      addBtn.className = 'add-chip';
      addBtn.innerText = '+ Add';
      addBtn.onclick = promptForSpeakerName;
      chipContainer.appendChild(addBtn);
    }
  }
};

window.onbeforeunload = () => {
  Picovoice.release();
};
