package games.strategy.triplea.ai.proAI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.BasicPoliticalAI;
import games.strategy.triplea.ai.proAI.logging.ProLogger;
import games.strategy.triplea.ai.proAI.util.ProAttackOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProUtils;
import games.strategy.triplea.attatchments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.DelegateFinder;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PoliticsDelegate;
import games.strategy.util.CompositeMatchAnd;
import games.strategy.util.Match;

/**
 * Pro politics AI.
 */
public class ProPoliticsAI {

  private final ProAI ai;
  private final ProUtils utils;
  private final ProAttackOptionsUtils attackOptionsUtils;

  public ProPoliticsAI(final ProAI ai, final ProUtils utils, final ProAttackOptionsUtils attackOptionsUtils) {
    this.ai = ai;
    this.utils = utils;
    this.attackOptionsUtils = attackOptionsUtils;
  }

  public List<PoliticalActionAttachment> politicalActions() {

    final GameData data = ai.getGameData();
    final PlayerID player = ai.getPlayerID();
    final float numPlayers = data.getPlayerList().getPlayers().size();
    final double round = data.getSequence().getRound();
    final PoliticsDelegate politicsDelegate = DelegateFinder.politicsDelegate(data);
    final List<PoliticalActionAttachment> results = new ArrayList<PoliticalActionAttachment>();
    ProLogger.info("Politics for " + player.getName());

    // Find valid war actions
    final List<PoliticalActionAttachment> actionChoicesTowardsWar =
        BasicPoliticalAI.getPoliticalActionsTowardsWar(player, politicsDelegate.getTestedConditions(), data);
    ProLogger.trace("War options: " + actionChoicesTowardsWar);
    final List<PoliticalActionAttachment> validWarActions =
        Match.getMatches(
            actionChoicesTowardsWar,
            new CompositeMatchAnd<PoliticalActionAttachment>(Matches
                .AbstractUserActionAttachmentCanBeAttempted(politicsDelegate.getTestedConditions())));
    ProLogger.trace("Valid War options: " + validWarActions);

    // Divide war actions into enemy and neutral
    final Map<PoliticalActionAttachment, List<PlayerID>> enemyMap =
        new HashMap<PoliticalActionAttachment, List<PlayerID>>();
    final Map<PoliticalActionAttachment, List<PlayerID>> neutralMap =
        new HashMap<PoliticalActionAttachment, List<PlayerID>>();
    for (final PoliticalActionAttachment action : validWarActions) {
      final List<PlayerID> warPlayers = new ArrayList<PlayerID>();
      for (final String relationshipChange : action.getRelationshipChange()) {
        final String[] s = relationshipChange.split(":");
        final PlayerID player1 = data.getPlayerList().getPlayerID(s[0]);
        final PlayerID player2 = data.getPlayerList().getPlayerID(s[1]);
        final RelationshipType oldRelation = data.getRelationshipTracker().getRelationshipType(player1, player2);
        final RelationshipType newRelation = data.getRelationshipTypeList().getRelationshipType(s[2]);
        if (!oldRelation.equals(newRelation) && Matches.RelationshipTypeIsAtWar.match(newRelation)
            && (player1.equals(player) || player2.equals(player))) {
          PlayerID warPlayer = player2;
          if (warPlayer.equals(player)) {
            warPlayer = player1;
          }
          warPlayers.add(warPlayer);
        }
      }
      if (!warPlayers.isEmpty()) {
        if (utils.isNeutralPlayer(warPlayers.get(0))) {
          neutralMap.put(action, warPlayers);
        } else {
          enemyMap.put(action, warPlayers);
        }
      }
    }
    ProLogger.debug("Neutral options: " + neutralMap);
    ProLogger.debug("Enemy options: " + enemyMap);
    if (!enemyMap.isEmpty()) {

      // Find all attack options
      final Map<Territory, ProAttackTerritoryData> attackMap = new HashMap<Territory, ProAttackTerritoryData>();
      final Map<Unit, Set<Territory>> unitAttackMap = new HashMap<Unit, Set<Territory>>();
      final Map<Unit, Set<Territory>> transportAttackMap = new HashMap<Unit, Set<Territory>>();
      final Map<Unit, Set<Territory>> bombardMap = new HashMap<Unit, Set<Territory>>();
      final List<ProAmphibData> transportMapList = new ArrayList<ProAmphibData>();
      final Map<Territory, Set<Territory>> landRoutesMap = new HashMap<Territory, Set<Territory>>();
      final List<Territory> myUnitTerritories =
          Match.getMatches(data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player));
      attackOptionsUtils.findPotentialAttackOptions(player, myUnitTerritories, attackMap, unitAttackMap,
          transportAttackMap, bombardMap, landRoutesMap, transportMapList);
      final List<ProAttackTerritoryData> prioritizedTerritories =
          attackOptionsUtils.removeTerritoriesThatCantBeConquered(player, attackMap, unitAttackMap, transportAttackMap,
              true);
      ProLogger.trace(player.getName() + ", numAttackOptions=" + prioritizedTerritories.size()
          + ", options=" + prioritizedTerritories);

      // Find attack options per war action
      final Map<PoliticalActionAttachment, Double> attackPercentageMap =
          new HashMap<PoliticalActionAttachment, Double>();
      for (final PoliticalActionAttachment action : enemyMap.keySet()) {
        int count = 0;
        final List<PlayerID> enemyPlayers = enemyMap.get(action);
        for (final ProAttackTerritoryData patd : prioritizedTerritories) {
          if (Matches.isTerritoryOwnedBy(enemyPlayers).match(patd.getTerritory())
              || Matches.territoryHasUnitsThatMatch(Matches.unitOwnedBy(enemyPlayers)).match(patd.getTerritory())) {
            count++;
          }
        }
        final double attackPercentage = count / (prioritizedTerritories.size() + 1.0);
        attackPercentageMap.put(action, attackPercentage);
        ProLogger.trace(enemyPlayers + ", count=" + count + ", attackPercentage=" + attackPercentage);
      }

      // Decide whether to declare war on an enemy
      final List<PoliticalActionAttachment> options =
          new ArrayList<PoliticalActionAttachment>(attackPercentageMap.keySet());
      Collections.shuffle(options);
      for (final PoliticalActionAttachment action : options) {
        final double roundFactor = (round - 1) * .05; // 0, .05, .1, .15, etc
        final double warChance = roundFactor + attackPercentageMap.get(action) * (1 + 10 * roundFactor);
        final double random = Math.random();
        ProLogger.trace(enemyMap.get(action) + ", warChance=" + warChance + ", random=" + random);
        if (random <= warChance) {
          results.add(action);
          ProLogger.debug("---Declared war on " + enemyMap.get(action));
          break;
        }
      }
    } else if (!neutralMap.isEmpty()) {

      // Decide whether to declare war on a neutral
      final List<PoliticalActionAttachment> options = new ArrayList<PoliticalActionAttachment>(neutralMap.keySet());
      Collections.shuffle(options);
      final double random = Math.random();
      final double warChance = .01;
      ProLogger.debug("warChance=" + warChance + ", random=" + random);
      if (random <= warChance) {
        results.add(options.get(0));
        ProLogger.debug("Declared war on " + enemyMap.get(options.get(0)));
      }
    }

    // Old code used for non-war actions
    if (Math.random() < .5) {
      final List<PoliticalActionAttachment> actionChoicesOther =
          BasicPoliticalAI.getPoliticalActionsOther(player, politicsDelegate.getTestedConditions(), data);
      if (actionChoicesOther != null && !actionChoicesOther.isEmpty()) {
        Collections.shuffle(actionChoicesOther);
        int i = 0;
        final double random = Math.random();
        final int MAX_OTHER_ACTIONS_PER_TURN =
            (random < .3 ? 0 : (random < .6 ? 1 : (random < .9 ? 2 : (random < .99 ? 3 : (int) numPlayers))));
        final Iterator<PoliticalActionAttachment> actionOtherIter = actionChoicesOther.iterator();
        while (actionOtherIter.hasNext() && MAX_OTHER_ACTIONS_PER_TURN > 0) {
          final PoliticalActionAttachment action = actionOtherIter.next();
          if (!Matches.AbstractUserActionAttachmentCanBeAttempted(politicsDelegate.getTestedConditions()).match(action)) {
            continue;
          }
          if (action.getCostPU() > 0 && action.getCostPU() > player.getResources().getQuantity(Constants.PUS)) {
            continue;
          }
          i++;
          if (i > MAX_OTHER_ACTIONS_PER_TURN) {
            break;
          }
          results.add(action);
        }
      }
    }
    doActions(results);
    return results;
  }

  public void doActions(final List<PoliticalActionAttachment> actions) {
    final GameData data = ai.getGameData();
    final PoliticsDelegate politicsDelegate = DelegateFinder.politicsDelegate(data);
    for (final PoliticalActionAttachment action : actions) {
      ProLogger.debug("Performing action: " + action);
      politicsDelegate.attemptAction(action);
    }
  }
}
