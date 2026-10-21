#!/usr/bin/env python3
"""
Exercises the constraints the new migrations (V34 service requests, V35 menu options, V36 billing)
rely on, with real rows, on the schema the migrations produce (SQLite; see migrate_sqlite.py).

This tests BEHAVIOUR no static check can: that a second open "call waiter" request really is refused,
that deleting a cart line really removes its options, that an option deleted from the menu really leaves
the cart's copy behind with its link cut, that an out-of-range tax rate is really rejected.

SQLite is not MySQL: unique indexes treat NULLs as distinct in both, generated columns and CHECK work
alike, but a result here is evidence about the SQL, not a guarantee about MySQL's engine.
"""
import itertools
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from migrate_sqlite import build  # noqa: E402

counter = itertools.count(1)


class Fixture:
    """Creates a row in any table, first creating every parent row its foreign keys need."""

    def __init__(self, con):
        self.con = con
        # SQLite silently ignores this pragma inside an open transaction, and the migrations' seed
        # inserts leave one open. Commit first, then prove it took effect.
        self.con.commit()
        self.con.execute('PRAGMA foreign_keys = ON')
        assert self.con.execute('PRAGMA foreign_keys').fetchone()[0] == 1, 'foreign keys not enforced'
        self.info = {}

    def cols(self, table):
        if table not in self.info:
            self.info[table] = list(self.con.execute(f'pragma table_xinfo({table})'))
        return self.info[table]

    def fks(self, table):
        return {r[3]: (r[2], r[4]) for r in self.con.execute(f'pragma foreign_key_list({table})')}

    def row(self, table, **given):
        values = dict(given)
        fks = self.fks(table)
        for cid, name, typ, notnull, dflt, pk, hidden in self.cols(table):
            if hidden in (2, 3) or name in values:
                continue
            if name in fks:
                if notnull:
                    parent, pcol = fks[name]
                    values[name] = self.row(parent)
                continue
            if pk:
                continue                               # autoincrement
            if not notnull or dflt is not None:
                continue
            t = typ.upper()
            n = next(counter)
            if any(k in t for k in ('INT', 'BIT', 'BOOL')):
                values[name] = 1
            elif any(k in t for k in ('DECIMAL', 'NUMERIC', 'DOUBLE', 'FLOAT')):
                values[name] = 1
            elif any(k in t for k in ('TIMESTAMP', 'DATETIME', 'DATE', 'TIME')):
                values[name] = '2026-10-15 10:00:00'
            else:
                values[name] = f'{name[:6]}{n}'
        cols = ', '.join(values)
        marks = ', '.join('?' for _ in values)
        cur = self.con.execute(f'INSERT INTO {table} ({cols}) VALUES ({marks})', list(values.values()))
        return cur.lastrowid

    def count(self, table, where='1=1', *args):
        return self.con.execute(f'select count(*) from {table} where {where}', args).fetchone()[0]


results = []


def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))


def raises(fn):
    try:
        fn()
    except sqlite3.IntegrityError as e:
        return str(e)
    return None


def main():
    con, rep = build()
    if rep['errors']:
        print('migrations did not apply cleanly; run migrate_sqlite.py')
        return 1
    fx = Fixture(con)

    # ----------------------------------------------------------------- V34 service requests
    def sr(session, rtype, status='PENDING', number=None, **kw):
        return fx.row('service_requests', table_session_id=session, request_type=rtype, status=status,
                      request_number=number or f'SR-{next(counter):06d}', **kw)

    s1 = fx.row('table_sessions')
    s2 = fx.row('table_sessions')
    first = sr(s1, 'CALL_WAITER')
    check('V34 a second OPEN request of the same type at the same visit is refused',
          raises(lambda: sr(s1, 'CALL_WAITER')) is not None)
    check('V34 ... even while the first is ACKNOWLEDGED or IN_PROGRESS',
          (con.execute("update service_requests set status='ACKNOWLEDGED' where id=?", (first,)) and
           raises(lambda: sr(s1, 'CALL_WAITER')) is not None) and
          (con.execute("update service_requests set status='IN_PROGRESS' where id=?", (first,)) and
           raises(lambda: sr(s1, 'CALL_WAITER')) is not None))
    check('V34 a different type at the same visit is allowed', sr(s1, 'DRINKING_WATER') > 0)
    check('V34 the same type at a different visit is allowed', sr(s2, 'CALL_WAITER') > 0)
    con.execute("update service_requests set status='COMPLETED' where id=?", (first,))
    check('V34 once the first is COMPLETED the guest can ask again', sr(s1, 'CALL_WAITER') > 0)
    third = con.execute("select id from service_requests where table_session_id=? and request_type='CALL_WAITER' and status='PENDING'", (s1,)).fetchone()[0]
    con.execute("update service_requests set status='CANCELLED' where id=?", (third,))
    check('V34 a CANCELLED request does not block a new one', sr(s1, 'CALL_WAITER') > 0)
    ok_other = [sr(s2, 'OTHER') for _ in range(4)]
    check('V34 any number of OTHER (free text) requests may be open', len(set(ok_other)) == 4)
    check('V34 a request number can never be reused',
          raises(lambda: sr(s2, 'CUTLERY', number='SR-DUP001') and sr(s2, 'NAPKINS', number='SR-DUP001')) is not None)
    key = con.execute("select open_dedupe_key from service_requests where table_session_id=? and request_type='DRINKING_WATER'", (s1,)).fetchone()[0]
    check('V34 the dedupe key is "<visit>:<type>" while open', key == f'{s1}:DRINKING_WATER', key)
    done_key = con.execute("select open_dedupe_key from service_requests where id=?", (first,)).fetchone()[0]
    check('V34 the dedupe key is NULL once a request is finished', done_key is None)
    ev_req = sr(s2, 'ASSISTANCE')
    ev = fx.row('service_request_events', service_request_id=ev_req, to_status='PENDING', actor_label='Rahul')
    con.execute('delete from service_request_events where id=?', (ev,))
    check('V34 history rows reference a real request',
          raises(lambda: fx.row('service_request_events', service_request_id=999999, to_status='PENDING', actor_label='x')) is not None)

    # ----------------------------------------------------------------- V35 menu options
    group = fx.row('menu_option_groups', kind='SINGLE')
    opt = fx.row('menu_options', group_id=group, name='Raita', price_delta=30)
    cart_item = fx.row('cart_items')
    check('V35 an existing cart line gets options_key "" by default',
          con.execute('select options_key from cart_items where id=?', (cart_item,)).fetchone()[0] == '')
    cio = fx.row('cart_item_options', cart_item_id=cart_item, option_id=opt, group_name='Extras', option_name='Raita', price_delta=30)
    con.execute('delete from menu_options where id=?', (opt,))
    row = con.execute('select option_id, option_name, price_delta from cart_item_options where id=?', (cio,)).fetchone()
    check('V35 deleting an option cuts the cart copy loose (link NULL) but keeps its name and price',
          row == (None, 'Raita', 30), row)
    con.execute('delete from cart_items where id=?', (cart_item,))
    check('V35 deleting a cart line deletes its option copies', fx.count('cart_item_options', 'id=?', cio) == 0)

    group2 = fx.row('menu_option_groups', kind='MULTI')
    fx.row('menu_options', group_id=group2, name='Egg', price_delta=20)
    fx.row('menu_options', group_id=group2, name='Salan', price_delta=25)
    con.execute('delete from menu_option_groups where id=?', (group2,))
    check('V35 deleting a group deletes its options', fx.count('menu_options', 'group_id=?', group2) == 0)

    oi = fx.row('order_items')
    oio = fx.row('order_item_options', order_item_id=oi, group_name='Portion', option_name='Family', price_delta=250)
    con.execute('delete from order_items where id=?', (oi,))
    check('V35 deleting an order line deletes its option copies', fx.count('order_item_options', 'id=?', oio) == 0)
    check('V35 an option copy on an order has no link to the menu to break',
          'option_id' not in [c[1] for c in con.execute('pragma table_info(order_item_options)')])
    check('V35 a dish defaults to no chef pick, no special, undeclared allergens',
          tuple(con.execute('select chef_pick, todays_special, allergens, dietary_labels from menu_items where id=?',
                            (fx.row('menu_items'),)).fetchone()) == (0, 0, None, None))

    # ----------------------------------------------------------------- V36 billing
    r1 = fx.row('restaurants')
    cfg = fx.row('restaurant_billing_settings', restaurant_id=r1)
    row = con.execute('select tax_name, tax_rate, service_charge_rate, prices_include_tax from restaurant_billing_settings where id=?', (cfg,)).fetchone()
    check('V36 every rate defaults to 0 and prices default to NOT including tax', row == ('Tax', 0, 0, 0), row)
    check('V36 a restaurant can have only one billing settings row',
          raises(lambda: fx.row('restaurant_billing_settings', restaurant_id=r1)) is not None)
    for label, field, value in [('tax rate above 100', 'tax_rate', 100.01), ('negative tax rate', 'tax_rate', -1),
                                ('service charge above 100', 'service_charge_rate', 101),
                                ('negative service charge', 'service_charge_rate', -0.5)]:
        check(f'V36 {label} is refused by the database itself',
              raises(lambda f=field, v=value: fx.row('restaurant_billing_settings', **{f: v})) is not None)
    check('V36 100% is allowed', fx.row('restaurant_billing_settings', tax_rate=100, service_charge_rate=100) > 0)
    cid = fx.row('carts')
    oid = fx.row('orders')
    for t, i in (('carts', cid), ('orders', oid)):
        row = con.execute(f'select service_charge, tax_included, tax_rate, service_charge_rate, prices_include_tax from {t} where id=?', (i,)).fetchone()
        check(f'V36 an existing {t[:-1]} gets zero charges and no tax rules', tuple(row) == (0, 0, 0, 0, 0), row)

    # ----------------------------------------------------------------- V37 cancellation and history
    order_id = fx.row('orders')
    row = con.execute('select cancel_reason, cancel_note, cancelled_at, cancelled_by from orders where id=?', (order_id,)).fetchone()
    check('V37 an existing order has no cancellation recorded', tuple(row) == (None, None, None, None), row)
    ev = fx.row('order_status_events', order_id=order_id, to_status='PENDING', actor_label='Rahul')
    first = con.execute('select from_status, to_status, reason, note from order_status_events where id=?', (ev,)).fetchone()
    check('V37 the first history row has no "from" status and no reason', tuple(first) == (None, 'PENDING', None, None), first)
    ev2 = fx.row('order_status_events', order_id=order_id, from_status='PENDING', to_status='CANCELLED',
                 actor_label='owner@example.com', reason='OUT_OF_STOCK', note='Lamb is late')
    back = con.execute('select reason, note from order_status_events where id=?', (ev2,)).fetchone()
    check('V37 a cancellation keeps its reason code and note exactly as written', tuple(back) == ('OUT_OF_STOCK', 'Lamb is late'), back)
    check('V37 a history row must reference a real order',
          raises(lambda: fx.row('order_status_events', order_id=999999, to_status='PENDING', actor_label='x')) is not None)
    check('V37 a history row cannot be saved without saying who did it',
          raises(lambda: con.execute("insert into order_status_events (order_id, to_status) values (?, 'READY')", (order_id,))) is not None)
    con.execute("update orders set cancel_reason='GUEST_REQUEST', cancel_note='left', cancelled_by='o@x.com' where id=?", (order_id,))
    check('V37 an order records why, who and (separately) when it was cancelled',
          tuple(con.execute('select cancel_reason, cancel_note, cancelled_by from orders where id=?', (order_id,)).fetchone())
          == ('GUEST_REQUEST', 'left', 'o@x.com'))

    # ----------------------------------------------------------------- V38 stored images
    img = fx.row('stored_images', public_key='a' * 32, content_type='image/jpeg', width=800, height=600,
                 size_bytes=3, data=b'\xff\xd8\xff')
    back = con.execute('select width, height, size_bytes, data from stored_images where id=?', (img,)).fetchone()
    check('V38 a photo round-trips its dimensions and exact bytes', tuple(back) == (800, 600, 3, b'\xff\xd8\xff'), back)
    check('V38 two photos can never share a public key',
          raises(lambda: fx.row('stored_images', public_key='a' * 32, content_type='image/jpeg', width=1, height=1,
                                size_bytes=1, data=b'x')) is not None)
    check('V38 a photo must belong to a real restaurant',
          raises(lambda: fx.row('stored_images', restaurant_id=999999, public_key='b' * 32, content_type='image/jpeg',
                                width=1, height=1, size_bytes=1, data=b'x')) is not None)
    check('V38 a photo cannot be stored with no bytes at all',
          raises(lambda: con.execute(
              "insert into stored_images (public_key, restaurant_id, content_type, width, height, size_bytes) "
              "values ('c', (select min(id) from restaurants), 'image/jpeg', 1, 1, 1)")) is not None)
    check('V38 a zero-length photo is refused by the database itself',
          raises(lambda: fx.row('stored_images', public_key='d' * 32, content_type='image/jpeg', width=1, height=1,
                                size_bytes=0, data=b'')) is not None)

    # ----------------------------------------------------------------- V39 order feedback
    fb_order = fx.row('orders')
    fb = fx.row('order_feedback', order_id=fb_order, rating=4, comment='Lovely')
    back = con.execute('select rating, comment from order_feedback where id=?', (fb,)).fetchone()
    check('V39 a rating and comment round-trip', tuple(back) == (4, 'Lovely'), back)
    check('V39 an order can only ever have one feedback row',
          raises(lambda: fx.row('order_feedback', order_id=fb_order, rating=5)) is not None)
    for bad in (0, 6, -1, 10):
        check(f'V39 the database itself refuses a rating of {bad}',
              raises(lambda b=bad: fx.row('order_feedback', order_id=fx.row('orders'), rating=b)) is not None)
    for good in (1, 2, 3, 4, 5):
        check(f'V39 a rating of {good} is accepted',
              raises(lambda g=good: fx.row('order_feedback', order_id=fx.row('orders'), rating=g)) is None)
    check('V39 a rating cannot be left out',
          raises(lambda: con.execute(
              "insert into order_feedback (order_id, restaurant_id) values (?, (select min(id) from restaurants))",
              (fx.row('orders'),))) is not None)
    check('V39 a comment is optional', raises(lambda: fx.row('order_feedback', order_id=fx.row('orders'), rating=3, comment=None)) is None)
    check('V39 feedback must belong to a real order',
          raises(lambda: fx.row('order_feedback', order_id=999999, rating=3)) is not None)
    check('V39 feedback must belong to a real restaurant',
          raises(lambda: fx.row('order_feedback', order_id=fx.row('orders'), restaurant_id=999999, rating=3)) is not None)

    # ----------------------------------------------------------------- V40 tax registration number
    bs = fx.row('restaurant_billing_settings')
    check('V40 an existing restaurant has no registration number (nothing is printed)',
          con.execute('select tax_registration_number from restaurant_billing_settings where id=?', (bs,)).fetchone()[0] is None)
    con.execute("update restaurant_billing_settings set tax_registration_number='29ABCDE1234F1Z5' where id=?", (bs,))
    check('V40 a registration number round-trips exactly',
          con.execute('select tax_registration_number from restaurant_billing_settings where id=?', (bs,)).fetchone()[0] == '29ABCDE1234F1Z5')
    con.execute("update restaurant_billing_settings set tax_registration_number=NULL where id=?", (bs,))
    check('V40 it can be cleared again',
          con.execute('select tax_registration_number from restaurant_billing_settings where id=?', (bs,)).fetchone()[0] is None)


    # ----------------------------------------------------------------- V41 landing page, feedback, rewards
    rest = fx.row('restaurants')
    ls = fx.row('restaurant_landing_settings', restaurant_id=rest)
    d = con.execute('select landing_enabled, wifi_enabled, rewards_enabled, feedback_enabled, wifi_security, '
                    'rewards_spend_per_point, rewards_redeem_points, google_review_url, wifi_password '
                    'from restaurant_landing_settings where id=?', (ls,)).fetchone()
    check('V41 a new settings row has safe defaults (Wi-Fi and rewards off, nothing configured)',
          tuple(d) == (1, 0, 0, 1, 'WPA', 10, 100, None, None), tuple(d))
    check('V41 a restaurant has at most one settings row',
          raises(lambda: fx.row('restaurant_landing_settings', restaurant_id=rest)) is not None)
    check('V41 settings must belong to a real restaurant',
          raises(lambda: fx.row('restaurant_landing_settings', restaurant_id=999999)) is not None)
    check('V41 a review link is stored exactly as given (no Place ID involved)',
          raises(lambda: con.execute("update restaurant_landing_settings set google_review_url='https://g.page/r/Cabc/review' where id=?", (ls,))) is None
          and con.execute('select google_review_url from restaurant_landing_settings where id=?', (ls,)).fetchone()[0] == 'https://g.page/r/Cabc/review')
    check('V41 a reward cannot cost zero points',
          raises(lambda: con.execute('update restaurant_landing_settings set rewards_redeem_points=0 where id=?', (ls,))) is not None)
    check('V41 points cannot be free to earn (spend per point must be above zero)',
          raises(lambda: con.execute('update restaurant_landing_settings set rewards_spend_per_point=0 where id=?', (ls,))) is not None)

    af = fx.row('anonymous_feedback', restaurant_id=rest, rating=2, category='SERVICE')
    row = con.execute('select order_id, comment, contact from anonymous_feedback where id=?', (af,)).fetchone()
    check('V41 anonymous feedback keeps no order, comment or contact unless given', tuple(row) == (None, None, None), tuple(row))
    for bad in (0, 6, -1):
        check(f'V41 anonymous feedback refuses a rating of {bad}',
              raises(lambda b=bad: fx.row('anonymous_feedback', restaurant_id=rest, rating=b, category='FOOD')) is not None)
    check('V41 many feedback rows may name the same order (a table can send several)',
          raises(lambda: [fx.row('anonymous_feedback', restaurant_id=rest, rating=4, category='FOOD', order_id=fb_order)
                          for _ in range(2)]) is None)
    check('V41 feedback must belong to a real restaurant',
          raises(lambda: fx.row('anonymous_feedback', restaurant_id=999999, rating=3, category='FOOD')) is not None)

    mem = fx.row('reward_members', restaurant_id=rest, member_code='ABCD2345', secret_hash='h' * 64)
    check('V41 a new member starts with zero points',
          tuple(con.execute('select points_balance, lifetime_points from reward_members where id=?', (mem,)).fetchone()) == (0, 0))
    check('V41 a member code is unique within a restaurant',
          raises(lambda: fx.row('reward_members', restaurant_id=rest, member_code='ABCD2345', secret_hash='x' * 64)) is not None)
    check('V41 one device (secret) can be only one member of a restaurant',
          raises(lambda: fx.row('reward_members', restaurant_id=rest, member_code='ZZZZ2345', secret_hash='h' * 64)) is not None)
    other = fx.row('restaurants')
    check('V41 the same code and secret may exist at a different restaurant',
          raises(lambda: fx.row('reward_members', restaurant_id=other, member_code='ABCD2345', secret_hash='h' * 64)) is None)
    check('V41 the database refuses a negative balance',
          raises(lambda: con.execute('update reward_members set points_balance=-1 where id=?', (mem,))) is not None)

    earn_order = fx.row('orders')
    fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='EARN', points=12, order_id=earn_order)
    check('V41 one order can earn points only once, ever',
          raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='EARN', points=12, order_id=earn_order)) is not None)
    other_member = fx.row('reward_members', restaurant_id=rest, member_code='QRST6789', secret_hash='k' * 64)
    check('V41 a second member cannot claim the same order either',
          raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=other_member, kind='EARN', points=12, order_id=earn_order)) is not None)
    check('V41 redemptions (no order) may repeat',
          raises(lambda: [fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='REDEEM', points=-100) for _ in range(2)]) is None)
    check('V41 an EARN must add points', raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='EARN', points=-5, order_id=fx.row('orders'))) is not None)
    check('V41 an EARN of zero is refused', raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='EARN', points=0, order_id=fx.row('orders'))) is not None)
    check('V41 a REDEEM must take points away', raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='REDEEM', points=50)) is not None)
    check('V41 only EARN and REDEEM exist', raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=mem, kind='GIFT', points=5)) is not None)
    check('V41 a ledger row must belong to a real member',
          raises(lambda: fx.row('reward_ledger', restaurant_id=rest, member_id=999999, kind='REDEEM', points=-1)) is not None)

    width = max(len(n) for n, _, _ in results)
    failed = 0
    print('== Behaviour of the new tables (V34 to V41) ==')
    for name, ok, detail in results:
        print(f"   {'ok ' if ok else '[!]'} {name}" + ('' if ok else f'   {detail}'))
        failed += 0 if ok else 1
    print(f"   {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
