package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.pluginbase.func.GuiManager;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.sweet.playermarket.Messages;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.api.IShopSellConfirmAdapter;
import top.mrxiaom.sweet.playermarket.data.EnumMarketType;
import top.mrxiaom.sweet.playermarket.data.MarketItem;
import top.mrxiaom.sweet.playermarket.data.MarketItemBuilder;
import top.mrxiaom.sweet.playermarket.data.NoticeFlag;
import top.mrxiaom.sweet.playermarket.database.MarketplaceDatabase;
import top.mrxiaom.sweet.playermarket.economy.IEconomy;
import top.mrxiaom.sweet.playermarket.func.NoticeManager;
import top.mrxiaom.sweet.playermarket.func.ShopAdapterRegistry;
import top.mrxiaom.sweet.playermarket.gui.api.AbstractGuiSearch;
import top.mrxiaom.sweet.playermarket.utils.Utils;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ActionTakeDown extends AbstractActionWithMarketItem {
    public static final ActionTakeDown INSTANCE = new ActionTakeDown();
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("take-down".equals(section.getString("type"))) {
                return INSTANCE;
            }
        } else {
            String s = String.valueOf(input);
            if (s.equals("[take-down]") || s.equals("take-down")) return INSTANCE;
        }
        return null;
    };
    private ActionTakeDown() {}

    @Override
    public void run(@NotNull Player player, @NotNull MarketItem item, @NotNull List<Pair<String, Object>> replacements) {
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        if (gui instanceof AbstractGuiSearch.SearchGui) {
            AbstractGuiSearch.SearchGui gm = (AbstractGuiSearch.SearchGui) gui;
            SweetPlayerMarket plugin = gm.plugin;

            gm.setActionLock(true);
            plugin.getScheduler().runTaskAsync(() -> {
                run(plugin, gm, player, item, replacements);
                gm.setActionLock(false);
            });
        }
    }

    private void run(
            SweetPlayerMarket plugin,
            AbstractGuiSearch.SearchGui gm,
            Player player,
            MarketItem item,
            List<Pair<String, Object>> replacements
    ) {
        try (Connection conn = plugin.getConnection()) {
            MarketplaceDatabase db = plugin.getMarketplace();
            MarketItem marketItem = db.getItem(conn, item.shopId());
            if (marketItem == null || marketItem.amount() == 0 || !marketItem.playerId().equals(plugin.getKey(player))) {
                Object i = Utils.get(replacements, "__internal__index");
                if (i instanceof Integer) {
                    if (marketItem != null) {
                        gm.setItem((int) i, marketItem);
                    } else {
                        gm.setItem((int) i, item.toBuilder().amount(0).build());
                    }
                }
                Messages.Gui.me__take_down__item_not_found.tm(player);
                return;
            }
            if (marketItem.type().equals(EnumMarketType.SELL)) {
                // 归还上架所需物品，不退手续费
                if (!takeDownSell(marketItem, player, marketItem.amount())) return;
            }
            if (marketItem.type().equals(EnumMarketType.BUY)) {
                // 归还上架所需货币，不退手续费
                if (!takeDownBuy(marketItem, player, marketItem.amount())) return;
            }
            // 归还已成交但尚未领取的物品或货币
            // 如果不归还这些内容，商品下架后这些数据将无法再领取，导致收购到的物品丢失
            if (!takeDownReceived(marketItem, player)) return;
            // 提交更改到数据库
            MarketItemBuilder builder = marketItem.toBuilder()
                    .noticeFlag(NoticeFlag.NOTHING)
                    .amount(0)
                    .params(marketItem.params());
            if (plugin.isUpdateOutdateTimeWhenSoldOut()) {
                builder.outdateTime(LocalDateTime.now());
            }
            if (!db.modifyItem(conn, builder.build()
            )) {
                Messages.Gui.me__take_down__submit_failed.tm(player);
                return;
            }
        } catch (SQLException e) {
            plugin.warn("玩家 " + player.getName() + " 在下架自己的商品 " + item.shopId() + " 时出现异常", e);
            plugin.getScheduler().closeInventory(player);
            Messages.Gui.me__take_down__exception.tm(player);
            return;
        }
        gm.doSearch();
        plugin.getScheduler().runTask(gm::open);
        NoticeManager.inst().updateCreated();
        Messages.Gui.me__take_down__success.tm(player);
    }

    protected static boolean takeDownSell(MarketItem marketItem, Player player, int count) {
        ShopAdapterRegistry.Entry entry = ShopAdapterRegistry.inst().getByMarketItem(marketItem);
        if (entry.hasFactoryParams()) {
            // 如果有商品适配器，则按适配器的实现来给予玩家物品
            IShopSellConfirmAdapter shopAdapter = entry.getSellConfirmAdapter(marketItem, player);
            if (shopAdapter == null) {
                Messages.Gui.sell__adapter_not_found.tm(player);
                return false;
            }
            shopAdapter.takeDownItem(count);
        } else {
            // 如果没有商品适配器，直接给予玩家物品
            for (int i = 0; i < count; i++) {
                Utils.giveItemsToPlayer(player, marketItem.item());
            }
        }
        return true;
    }

    protected static boolean takeDownBuy(MarketItem marketItem, Player player, int count) {
        IEconomy currency = marketItem.currency();
        if (currency == null) {
            String currencyName = SweetPlayerMarket.getInstance().displayNames().getCurrencyName(marketItem.currencyName());
            Messages.Gui.common__currency_not_found.tm(player, Pair.of("%currency%", currencyName));
            return false;
        }
        currency.giveMoney(player, marketItem.price() * count);
        return true;
    }

    /**
     * 判断商品是否存在「已成交但尚未领取」的物品或货币
     * <p>
     * 收购商店的商品，别人卖给店主且店主尚未领取的物品会保存在 <code>buy.received-items</code> 中
     * <p>
     * 出售商店的商品，买家已经付款且店主尚未领取的货币会保存在 <code>sell.received-currency</code> 中
     * @param marketItem 商品
     */
    protected static boolean hasReceivedData(MarketItem marketItem) {
        if (marketItem.type().equals(EnumMarketType.BUY)) {
            List<?> list = marketItem.params().getList("buy.received-items");
            return list != null && !list.isEmpty();
        }
        if (marketItem.type().equals(EnumMarketType.SELL)) {
            return marketItem.params().getDouble("sell.received-currency", 0.0) > 0;
        }
        return false;
    }

    /**
     * 归还商品中「已成交但尚未领取」的物品或货币，并在归还成功后清空相关参数
     * <p>
     * 如果商品下架时不归还这些内容，它们将无法再被领取，导致收购到的物品丢失
     * @param marketItem 商品
     * @param player 商品所有者
     * @return 是否归还成功，失败时不会清空参数，避免丢失数据
     */
    protected static boolean takeDownReceived(MarketItem marketItem, Player player) {
        ConfigurationSection params = marketItem.params();
        if (marketItem.type().equals(EnumMarketType.BUY)) {
            List<ItemStack> itemList = new ArrayList<>();
            for (Object obj : params.getList("buy.received-items", new ArrayList<>())) {
                if (obj instanceof ItemStack) {
                    itemList.add((ItemStack) obj);
                }
            }
            if (itemList.isEmpty()) return true;
            // 直接给予玩家物品，与领取时的实现保持一致
            Utils.giveItemsToPlayer(player, itemList);
            params.set("buy.received-items", null);
            return true;
        }
        if (marketItem.type().equals(EnumMarketType.SELL)) {
            double money = params.getDouble("sell.received-currency", 0.0);
            if (money <= 0) return true;
            IEconomy currency = marketItem.currency();
            if (currency == null) {
                String currencyName = SweetPlayerMarket.getInstance().displayNames().getCurrencyName(marketItem.currencyName());
                Messages.Gui.common__currency_not_found.tm(player, Pair.of("%currency%", currencyName));
                return false;
            }
            ShopAdapterRegistry.Entry entry = ShopAdapterRegistry.inst().getByMarketItem(marketItem);
            if (entry.hasFactoryParams()) {
                IShopSellConfirmAdapter shopAdapter = entry.getSellConfirmAdapter(marketItem, player);
                if (shopAdapter != null) {
                    // 与领取时的实现保持一致，允许适配器覆盖领取的货币数量
                    money = shopAdapter.overrideRewardMoney(money);
                }
            }
            currency.giveMoney(player, money);
            params.set("sell.received-currency", null);
            params.set("sell.received-count", null);
            params.set("sell.received-buyers", null);
            return true;
        }
        return true;
    }
}
