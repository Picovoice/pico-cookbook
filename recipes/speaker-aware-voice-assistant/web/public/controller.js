const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

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

  const layoutDashboard = document.getElementById('layoutDashboard');
  const chipContainer = document.getElementById('chipContainer');

  const layoutGreeting = document.getElementById('layoutGreeting');
  const tvSpeakerName = document.getElementById('tvSpeakerName');
  const tvGreetingPrefix = document.getElementById('tvGreetingPrefix');

  const enrollProgress = document.getElementById('enrollProgress');
  const volumeMeter = document.getElementById('volumeMeter');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  const buttonContainer = document.getElementById('buttonContainer');
  const btnStartEnroll = document.getElementById('btnStartEnroll');
  const btnStartTest = document.getElementById('btnStartTest');
  const btnClearAll = document.getElementById('btnClearAll');
  const btnCancel = document.getElementById('btnCancel');

  const nameModalOverlay = document.getElementById('nameModalOverlay');
  const speakerNameInput = document.getElementById('speakerNameInput');
  const userButton = document.getElementById('role-user');
  const adminButton = document.getElementById('role-admin');
  const btnModalCancel = document.getElementById('btnModalCancel');
  const btnModalEnroll = document.getElementById('btnModalEnroll');

  const MAX_SPEAKERS = 10;
  const EAGLE_THRESHOLD = 0.75;
  const SPEAKER_PALETTE = [
    '#377dff',
    '#10B981',
    '#8B5CF6',
    '#EC4899',
    '#F59E0B',
    '#06B6D4',
    '#EF4444',
    '#84CC16',
    '#F43F5E',
    '#6366F1',
  ];

  let speakerProfiles = [];
  let speakerNames = [];
  let speakerRoles = [];
  let pendingSpeakerName = '';
  let pendingSpeakerRole = '';

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
        
        await sleep(200);
        
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
      console.log("on enroll complete:");
      console.log(profile);

      speakerProfiles.push(profile);
      speakerNames.push(pendingSpeakerName);
      speakerRoles.push(pendingSpeakerRole);
      Picovoice.stopEnrollment();

      updateUIForState('IDLE');
    },
    onWakeWordRecognized: () => {
      noticeText.innerText = "Speak Command";

      // TODO: use the same voice command styled buttons from call-assist?
      extraText.innerText =
        '1. "do something that requires admin permission" [ Will only work if you are an admin ]\n' +
        '2. "do something just for me" [ Will trigger a personalized response ]\n' + 
        '3. "do something anyone can do" [ Will trigger a generic response ]\n';

      /*let bestScore = 0;
      let bestIndex = -1;
      if (scores) {
        for (let i = 0; i < scores.length; i++) {
          if (scores[i] > bestScore) {
            bestScore = scores[i];
            bestIndex = i;
          }
        }
      }

      if (bestIndex !== -1 && bestScore >= EAGLE_THRESHOLD) {
        showGreeting(speakerNames[bestIndex], SPEAKER_PALETTE[bestIndex]);
      }*/
    },
    beforeInferenceResponse: (intent, similarityScore) => {
      if (intent !== undefined) {
        console.log(`similarityScore = ${similarityScore}`)
      }

      noticeText.innerText = "";
    },
    onWordSpoken: (word) => {
      console.log(`on word spoken: ${word}`);

      if (extraText.innerHTML.length != 0) {
        extraText.innerHTML += " ";
      }
  
      extraText.innerHTML += word;
    },
    afterInferenceResponse: () => {
      noticeText.innerText = "Say the wakeword";
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
    console.log(speakerProfiles);
    console.log(speakerNames);
    console.log(speakerRoles);
    const zippedProfiles = speakerProfiles.map((p, i) => {
      profile: p;
      name: speakerNames[i];
      role: speakerRoles[i];
    });
    console.log("zippedProfiles");
    console.log(zippedProfiles);
  
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

  function promptForSpeakerName() {
    if (speakerProfiles.length >= MAX_SPEAKERS) {
      showError(`Maximum of ${MAX_SPEAKERS} speakers reached.`);
      return;
    }

    const defaultName = `Speaker ${speakerProfiles.length + 1}`;
    speakerNameInput.value = '';
    speakerNameInput.placeholder = defaultName;

    nameModalOverlay.classList.remove('hidden');
    if (pendingSpeakerName.length == 0) {
      userButton.onclick();
    }

    speakerNameInput.focus();
  }

  userButton.onclick = () => {
    pendingSpeakerRole = "user";
    userButton.setAttribute("selected", "");
    adminButton.removeAttribute("selected", "");
  };
  adminButton.onclick = () => {
    pendingSpeakerRole = "admin";
    adminButton.setAttribute("selected", "");
    userButton.removeAttribute("selected", "");
  };

  btnModalCancel.onclick = () => {
    nameModalOverlay.classList.add('hidden');
    updateUIForState('IDLE');
  };
  const submitModal = () => {
    pendingSpeakerName = speakerNameInput.value.trim();
    if (!pendingSpeakerName) {
      pendingSpeakerName = speakerNameInput.placeholder;
    }

    nameModalOverlay.classList.add('hidden');

    Picovoice.startEnrollment(pendingSpeakerRole);
    updateUIForState('ENROLLING');
  };
  btnModalEnroll.onclick = submitModal;
  speakerNameInput.addEventListener('keypress', e => {
    if (e.key === 'Enter') {
      e.preventDefault();
      submitModal();
    }
  });

  function updateUIForState(newState) {
    currentState = newState;
    const hasProfiles = speakerProfiles.length > 0;

    if (currentState === 'ENROLLING') {
      noticeText.innerHTML = `<div style="display: flex;">
                                <span>Hello ${pendingSpeakerName},</span>
                                <div style="margin-left: auto; display: inline-block;">Role: <b>${pendingSpeakerRole}</b></div>
                              </div>\n` +
                             "Read the following sentences aloud in your normal voice.\n" +
                             "Keep speaking until enrollment reaches 100%.";
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
      layoutDashboard.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      layoutGreeting.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Cancel';
      btnCancel.classList.remove('hidden');
      btnClearAll.classList.add('hidden');
    } else if (currentState === 'TESTING') {
      noticeText.innerText = "Say the wakeword";
      noticeText.style.textAlign = 'center';
      noticeText.classList.remove('hidden');
      extraText.classList.remove('hidden');
      extraText.innerHTML = "";
      enrollProgress.classList.add('hidden');

      titleText.classList.add('hidden');
      layoutDashboard.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      layoutGreeting.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Stop Testing';
      btnCancel.classList.remove('hidden');
      btnClearAll.classList.add('hidden');
    } else {
      noticeText.classList.toggle('hidden', hasProfiles);
      noticeText.innerText = 'Ready to Enroll';
      noticeText.style.textAlign = 'center';
      extraText.classList.add('hidden');

      enrollProgress.classList.add('hidden');
      volumeMeter.classList.add('hidden');
      btnCancel.classList.add('hidden');
      layoutGreeting.classList.add('hidden');

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

  // TODO: update this
  function showGreeting(speakerName, speakerColour) {
    volumeMeter.classList.add('hidden');
    noticeText.classList.add('hidden');
    btnCancel.classList.add('hidden');

    layoutGreeting.classList.remove('hidden');

    tvGreetingPrefix.classList.remove('hidden');
    tvSpeakerName.innerText = speakerName;
    tvSpeakerName.style.backgroundColor = speakerColour;
    tvSpeakerName.style.color = 'white';

    setTimeout(() => layoutGreeting.classList.add('pop'), 10);

    setTimeout(() => {
      layoutGreeting.classList.remove('pop');
      setTimeout(() => {
        layoutGreeting.classList.add('hidden');
        if (currentState === 'TESTING') {
          noticeText.classList.remove('hidden');
          volumeMeter.classList.remove('hidden');
          btnCancel.classList.remove('hidden');
        }
      }, 300);
    }, 2000);
  }
};

window.onbeforeunload = () => {
  Picovoice.release();
};
