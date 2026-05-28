export const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export type DemoCallbacks = {
  setLoadingState: (enabled: boolean) => Promise<void>,
  setStatusText: (newStatus: string) => void;
  clearStatus: () => void;
  setErrorText: (error: string) => void;
  clearError: () => void;

  onVolume: (volume: number) => void;
  onListening: (isListening: boolean) => void;

  createCard: (id: string, title: string, alternate?: string) => void,
  setActiveCard: (id: string) => void,
  setCompletedCard: (id: string, alternate: boolean) => void,
  setCardValue: (id: string, value: string) => void,

  goToInitScreen: () => void,
  currentScreen: () => string,
}

export let callbacks: DemoCallbacks = {
  setLoadingState: async (_) => undefined,
  setStatusText: (_) => undefined,
  clearStatus: () => undefined,
  setErrorText: (_) => undefined,
  clearError: () => undefined,

  onVolume: (_) => undefined,
  onListening: (_) => undefined,

  createCard: (_a, _b) => undefined,
  setActiveCard: (_) => undefined,
  setCompletedCard: (_a, _b) => undefined,
  setCardValue: (_a, _b) => undefined,

  goToInitScreen: () => undefined,
  currentScreen: () => "init",
};

export function updateCallbacks(newCallbacks: DemoCallbacks) {
  callbacks.setLoadingState = newCallbacks.setLoadingState;
  callbacks.setStatusText = newCallbacks.setStatusText;
  callbacks.clearStatus = newCallbacks.clearStatus;
  callbacks.setErrorText = newCallbacks.setErrorText;
  callbacks.clearError = newCallbacks.clearError;

  callbacks.onVolume = newCallbacks.onVolume;
  callbacks.onListening = newCallbacks.onListening;

  callbacks.createCard = newCallbacks.createCard;
  callbacks.setActiveCard = newCallbacks.setActiveCard;
  callbacks.setCompletedCard = newCallbacks.setCompletedCard;
  callbacks.setCardValue = newCallbacks.setCardValue;

  callbacks.goToInitScreen = newCallbacks.goToInitScreen;
  callbacks.currentScreen = newCallbacks.currentScreen;
}

export let isRunning = true;

export function setIsRunning(value: boolean) {
  isRunning = value;
}