package org.triplea.game.server;

import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.Action;
import javax.swing.JComponent;

import games.strategy.engine.chat.IChatPanel;
import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.startup.launcher.ILauncher;
import games.strategy.engine.framework.startup.mc.GameSelectorModel;
import games.strategy.engine.framework.startup.mc.IRemoteModelListener;
import games.strategy.engine.framework.startup.mc.ServerModel;
import games.strategy.engine.framework.startup.ui.ISetupPanel;
import games.strategy.engine.framework.startup.ui.InGameLobbyWatcher;
import games.strategy.engine.framework.startup.ui.InGameLobbyWatcherWrapper;
import games.strategy.engine.pbem.PBEMMessagePoster;
import games.strategy.util.Interruptibles;

/**
 * Server setup model.
 */
class HeadlessServerSetup implements IRemoteModelListener, ISetupPanel {
  private final List<Observer> listeners = new CopyOnWriteArrayList<>();
  private final ServerModel model;
  private final GameSelectorModel gameSelectorModel;
  private final InGameLobbyWatcherWrapper lobbyWatcher = new InGameLobbyWatcherWrapper();

  HeadlessServerSetup(final ServerModel model, final GameSelectorModel gameSelectorModel) {
    this.model = model;
    this.gameSelectorModel = gameSelectorModel;
    this.model.setRemoteModelListener(this);
    createLobbyWatcher();
    internalPlayerListChanged();
  }

  private void createLobbyWatcher() {
    lobbyWatcher.setInGameLobbyWatcher(InGameLobbyWatcher.newInGameLobbyWatcher(model.getMessenger(), null,
        lobbyWatcher.getInGameLobbyWatcher()));
    lobbyWatcher.setGameSelectorModel(gameSelectorModel);
  }

  synchronized void repostLobbyWatcher() {
    lobbyWatcher.shutDown();
    Interruptibles.sleep(3000);
    createLobbyWatcher();
  }

  @Override
  public void cancel() {
    model.setRemoteModelListener(IRemoteModelListener.NULL_LISTENER);
    model.cancel();
    lobbyWatcher.shutDown();
  }

  @Override
  public boolean canGameStart() {
    if (gameSelectorModel.getGameData() == null || model == null) {
      return false;
    }
    final Map<String, String> players = model.getPlayersToNodeListing();
    if (players == null || players.isEmpty() || players.values().contains(null)) {
      return false;
    }
    // make sure at least 1 player is enabled
    return model.getPlayersEnabledListing().values().contains(Boolean.TRUE);
  }

  @Override
  public void playerListChanged() {
    internalPlayerListChanged();
  }

  @Override
  public void playersTakenChanged() {
    internalPlayersTakenChanged();
  }

  private void internalPlayersTakenChanged() {
    notifyObservers();
  }

  private void internalPlayerListChanged() {
    internalPlayersTakenChanged();
  }

  @Override
  public IChatPanel getChatPanel() {
    return model.getChatPanel();
  }

  ServerModel getModel() {
    return model;
  }

  @Override
  public synchronized Optional<ILauncher> getLauncher() {
    return model.getLauncher()
        .map(launcher -> {
          launcher.setInGameLobbyWatcher(lobbyWatcher);
          return launcher;
        });
  }

  @Override
  public List<Action> getUserActions() {
    return null;
  }

  @Override
  public void addObserver(final Observer observer) {
    listeners.add(observer);
  }

  @Override
  public void notifyObservers() {
    for (final Observer observer : listeners) {
      observer.update(null, null);
    }
  }

  @Override
  public void preStartGame() {}

  @Override
  public void postStartGame() {
    final GameData data = gameSelectorModel.getGameData();
    data.getProperties().set(PBEMMessagePoster.PBEM_GAME_PROP_NAME, false);
  }

  @Override
  public JComponent getDrawable() {
    throw new UnsupportedOperationException("HeadlessServerSetup should not use UI components. "
        + "Bot setup code should not execute this code path.");
  }

  @Override
  public boolean showCancelButton() {
    return true;
  }
}