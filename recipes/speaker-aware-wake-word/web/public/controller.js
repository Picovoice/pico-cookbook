window.onload = () => {
  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');
  const initContainer = document.getElementById('initContainer');
  const appContainer = document.getElementById('appContainer');

  const titleText = document.getElementById('titleText');
  const statusText = document.getElementById('statusText');

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
  let pendingSpeakerName = '';
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
      updateUIForState('IDLE');
    },
    onWakeWordRecognized: scores => {
      let bestScore = 0;
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
    await Picovoice.startTesting(speakerProfiles);
    updateUIForState('TESTING');
  };

  btnClearAll.onclick = () => {
    speakerProfiles = [];
    speakerNames = [];
    updateUIForState('IDLE');
  };

  btnCancel.onclick = async () => {
    await Picovoice.stop();
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
    speakerNameInput.focus();
  }

  const submitModal = () => {
    pendingSpeakerName = speakerNameInput.value.trim();
    if (!pendingSpeakerName) {
      pendingSpeakerName = speakerNameInput.placeholder;
    }

    nameModalOverlay.classList.add('hidden');

    Picovoice.startEnrollment();
    updateUIForState('ENROLLING');
  };

  btnModalEnroll.onclick = submitModal;

  btnModalCancel.onclick = () => {
    nameModalOverlay.classList.add('hidden');
    updateUIForState('IDLE');
  };

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
      statusText.innerText = `Hello ${pendingSpeakerName}\n\nSay the wake word\nuntil the bar is full`;
      statusText.classList.remove('hidden');
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
      statusText.innerText = 'Listening for wake word...';
      statusText.classList.remove('hidden');
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
      statusText.classList.toggle('hidden', hasProfiles);
      statusText.innerText = 'Ready to Enroll';

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

  function showGreeting(speakerName, speakerColour) {
    volumeMeter.classList.add('hidden');
    statusText.classList.add('hidden');
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
          statusText.classList.remove('hidden');
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
