//! 配对密钥交换：Edwards25519 上的 SPAKE2。
//!
//! 为保证与设备端配对服务互操作，本实现按字节级复刻公开协议行为：
//! * 临时私钥：64 字节随机数经模 L 归约后左移 3 位（乘 8），用于消除余因子；
//! * password scalar：SHA512(password) 模 L 归约后，按低 3 位分别累加
//!   L / 2L / 4L，使其为 8 的倍数（历史互操作约定，不可简化为再取模）；
//! * Alice 掩码点用 M、解掩码用 N；Bob 相反；
//! * transcript 采用 8 字节小端长度前缀，最终 SHA512 得 64 字节 key material。
//!
//! 标量乘法刻意以完整 256 位未归约整数进行（而非 `Scalar` 的模 L 语义），
//! 因为 password scalar 可能大于 L；这里用显式 double-and-add 保证可控。

use curve25519_dalek::constants::ED25519_BASEPOINT_POINT;
use curve25519_dalek::edwards::{CompressedEdwardsY, EdwardsPoint};
use curve25519_dalek::scalar::Scalar;
use sha2::{Digest, Sha512};

use crate::crypto::update_with_length_prefix;
use crate::error::WirelessError;

/// 固定点 M / N 的 Edwards 压缩编码。
pub const M_ENCODED: [u8; 32] = [
    0x5a, 0xda, 0x7e, 0x4b, 0xf6, 0xdd, 0xd9, 0xad, 0xb6, 0x62, 0x6d, 0x32, 0x13, 0x1c, 0x6b, 0x5c,
    0x51, 0xa1, 0xe3, 0x47, 0xa3, 0x47, 0x8f, 0x53, 0xcf, 0xcf, 0x44, 0x1b, 0x88, 0xee, 0xd1, 0x2e,
];
pub const N_ENCODED: [u8; 32] = [
    0x10, 0xe3, 0xdf, 0x0a, 0xe3, 0x7d, 0x8e, 0x7a, 0x99, 0xb5, 0xfe, 0x74, 0xb4, 0x46, 0x72, 0x10,
    0x3d, 0xbd, 0xdc, 0xbd, 0x06, 0xaf, 0x68, 0x0d, 0x71, 0x32, 0x9a, 0x11, 0x69, 0x3b, 0xc7, 0x78,
];

/// 名称包含结尾 NUL，长度 16。
pub const CLIENT_NAME: &[u8] = b"adb pair client\0";
pub const SERVER_NAME: &[u8] = b"adb pair server\0";

/// 素阶子群的阶 L（小端 4×u64）。
const ORDER_WORDS: [u64; 4] = [
    0x5812_631a_5cf5_d3ed,
    0x14de_f9de_a2f7_9cd6,
    0x0000_0000_0000_0000,
    0x1000_0000_0000_0000,
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Alice,
    Bob,
}

fn identity() -> EdwardsPoint {
    ED25519_BASEPOINT_POINT * Scalar::from(0u64)
}

fn decode(bytes: &[u8; 32]) -> Result<EdwardsPoint, WirelessError> {
    CompressedEdwardsY(*bytes)
        .decompress()
        .ok_or_else(|| WirelessError::Crypto("对端 SPAKE2 点不在曲线上".into()))
}

/// 完整 256 位未归约标量的 double-and-add（MSB 优先）。
fn mul_unreduced(point: &EdwardsPoint, scalar_le: &[u8; 32]) -> EdwardsPoint {
    let mut acc = identity();
    for i in (0..256).rev() {
        acc = &acc + &acc;
        if (scalar_le[i / 8] >> (i % 8)) & 1 == 1 {
            acc = &acc + point;
        }
    }
    acc
}

fn add256(a: &mut [u64; 4], b: &[u64; 4]) {
    let mut carry: u128 = 0;
    for i in 0..4 {
        let s = a[i] as u128 + b[i] as u128 + carry;
        a[i] = s as u64;
        carry = s >> 64;
    }
}

fn double256(a: &mut [u64; 4]) {
    let copy = *a;
    add256(a, &copy);
}

fn words_from_bytes(b: &[u8; 32]) -> [u64; 4] {
    let mut w = [0u64; 4];
    for i in 0..4 {
        w[i] = u64::from_le_bytes(b[i * 8..i * 8 + 8].try_into().unwrap());
    }
    w
}

fn words_to_bytes(w: &[u64; 4]) -> [u8; 32] {
    let mut b = [0u8; 32];
    for i in 0..4 {
        b[i * 8..i * 8 + 8].copy_from_slice(&w[i].to_le_bytes());
    }
    b
}

/// 把 SHA512(password) 归约后再做低 3 位清零，返回 256 位未归约标量字节。
fn password_scalar(password_hash: &[u8; 64]) -> [u8; 32] {
    let reduced = Scalar::from_bytes_mod_order_wide(password_hash).to_bytes();
    let mut pw = words_from_bytes(&reduced);
    let mut order = ORDER_WORDS;
    if pw[0] & 1 == 1 {
        add256(&mut pw, &order);
    }
    double256(&mut order);
    if pw[0] & 2 == 2 {
        add256(&mut pw, &order);
    }
    double256(&mut order);
    if pw[0] & 4 == 4 {
        add256(&mut pw, &order);
    }
    debug_assert_eq!(pw[0] & 7, 0, "password scalar 必须为 8 的倍数");
    words_to_bytes(&pw)
}

/// 临时私钥：64 字节随机数模 L 归约后左移 3 位。
fn private_key(random64: &[u8; 64]) -> [u8; 32] {
    let reduced = Scalar::from_bytes_mod_order_wide(random64).to_bytes();
    let mut words = words_from_bytes(&reduced);
    // 左移 3 位：逐 word 处理，等价于 BoringSSL 的 left_shift_3。
    let mut carry = 0u64;
    for w in words.iter_mut() {
        let next = *w >> 61;
        *w = (*w << 3) | carry;
        carry = next;
    }
    words_to_bytes(&words)
}

/// `generate_msg` 的结果：保留上下文供 `process_msg` 使用。
pub struct Spake2 {
    role: Role,
    private_key: [u8; 32],
    password_scalar: [u8; 32],
    password_hash: [u8; 64],
    my_msg: [u8; 32],
    my_name: &'static [u8],
    their_name: &'static [u8],
}

pub struct Generated {
    pub ctx: Spake2,
    pub msg: [u8; 32],
}

/// 生成己方公开消息。`random64` 必须是 64 字节高质量随机数。
pub fn generate(
    password: &[u8],
    role: Role,
    random64: &[u8; 64],
) -> Result<Generated, WirelessError> {
    let m = decode(&M_ENCODED)?;
    let n = decode(&N_ENCODED)?;
    let priv_key = private_key(random64);

    let mut hash = [0u8; 64];
    hash.copy_from_slice(&Sha512::digest(password));
    let pw = password_scalar(&hash);

    let mask_point = match role {
        Role::Alice => &m,
        Role::Bob => &n,
    };
    let p = mul_unreduced(&ED25519_BASEPOINT_POINT, &priv_key);
    let mask = mul_unreduced(mask_point, &pw);
    let pstar = &p + &mask;
    let my_msg = pstar.compress().to_bytes();

    let (my_name, their_name) = match role {
        Role::Alice => (CLIENT_NAME, SERVER_NAME),
        Role::Bob => (SERVER_NAME, CLIENT_NAME),
    };
    Ok(Generated {
        ctx: Spake2 {
            role,
            private_key: priv_key,
            password_scalar: pw,
            password_hash: hash,
            my_msg,
            my_name,
            their_name,
        },
        msg: my_msg,
    })
}

/// 处理对端公开消息，产出 64 字节 key material。
pub fn process(ctx: &Spake2, their_msg: &[u8; 32]) -> Result<[u8; 64], WirelessError> {
    let m = decode(&M_ENCODED)?;
    let n = decode(&N_ENCODED)?;
    let q_star = decode(their_msg)?;
    let peer_mask_point = match ctx.role {
        Role::Alice => &n,
        Role::Bob => &m,
    };
    let peers_mask = mul_unreduced(peer_mask_point, &ctx.password_scalar);
    // Q = Q* - peers_mask：Edwards 点取负为 (-X, Y, Z, -T)。
    let q = &q_star + &(-peers_mask);
    let dh = mul_unreduced(&q, &ctx.private_key);
    let dh_encoded = dh.compress().to_bytes();

    let mut sha = Sha512::new();
    match ctx.role {
        Role::Alice => {
            update_with_length_prefix(&mut sha, ctx.my_name);
            update_with_length_prefix(&mut sha, ctx.their_name);
            update_with_length_prefix(&mut sha, &ctx.my_msg);
            update_with_length_prefix(&mut sha, their_msg);
        }
        Role::Bob => {
            update_with_length_prefix(&mut sha, ctx.their_name);
            update_with_length_prefix(&mut sha, ctx.my_name);
            update_with_length_prefix(&mut sha, their_msg);
            update_with_length_prefix(&mut sha, &ctx.my_msg);
        }
    }
    update_with_length_prefix(&mut sha, &dh_encoded);
    update_with_length_prefix(&mut sha, &ctx.password_hash);
    let digest = sha.finalize();
    let mut out = [0u8; 64];
    out.copy_from_slice(&digest);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    // 由 /tmp/spake_ref.py 的独立 Edwards25519 参考实现生成的固定向量。
    const PASSWORD_HEX: &str = "313233343536030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bc";
    const ALICE_RAND_HEX: &str = "0104070a0d101316191c1f2225282b2e3134373a3d404346494c4f5255585b5e6164676a6d707376797c7f8285888b8e9194979a9da0a3a6a9acafb2b5b8bbbe";
    const BOB_RAND_HEX: &str = "d3cec9c4bfbab5b0aba6a19c97928d88837e79746f6a65605b56514c47423d38332e29241f1a15100b0601fcf7f2ede8e3ded9d4cfcac5c0bbb6b1aca7a29d98";
    const ALICE_PRIV_HEX: &str = "801ebe76bd6cc86263bcc4799810fbfc72355003d991df1a2af1646705278b2d";
    const BOB_PRIV_HEX: &str = "c8c769f81dcfc5f85074596032d877dfcd73f6d849600595435f4ea80bf11528";
    const PW_SCALAR_HEX: &str = "d8c3626e3875ea4a963c322fde9a08713446dfea427439ad7bff6d8b5061d77f";
    const ALICE_MSG_HEX: &str = "303006c638ad78a0e6cf1ff87263a918fbcdb8de33eb77d3054b38ebdd538994";
    const BOB_MSG_HEX: &str = "0e49435c516b93eed5742a3d06969bc8e37aa7adc6600a70aabb39b3e68038da";
    const KEY_HEX: &str = "0b627236324a410c770151ef25cd460ed589b2f37909ba3ee8e369aad3f767fba8b930b93f31d393e51edc46107573b02ff22fd8b865334e47df8dccbfa2640f";
    const WRONG_KEY_HEX: &str = "f6a70fbb2967f918130bfe6d08384592de5e702c72d27e4e5c6688e42dbef83700f7bedd121d0084aba74f9db770682f67d55b9cbb456aa5b40258842ceb19ac";

    fn fixed<const N: usize>(h: &str) -> [u8; N] {
        let v = hex::decode(h).unwrap();
        let mut out = [0u8; N];
        out.copy_from_slice(&v);
        out
    }

    fn password() -> Vec<u8> {
        hex::decode(PASSWORD_HEX).unwrap()
    }

    #[test]
    fn fixed_points_roundtrip() {
        assert_eq!(decode(&M_ENCODED).unwrap().compress().to_bytes(), M_ENCODED);
        assert_eq!(decode(&N_ENCODED).unwrap().compress().to_bytes(), N_ENCODED);
    }

    #[test]
    fn scalar_helpers_match_reference() {
        assert_eq!(private_key(&fixed::<64>(ALICE_RAND_HEX)), fixed::<32>(ALICE_PRIV_HEX));
        assert_eq!(private_key(&fixed::<64>(BOB_RAND_HEX)), fixed::<32>(BOB_PRIV_HEX));
        let digest = Sha512::digest(&password());
        let mut ph = [0u8; 64];
        ph.copy_from_slice(&digest);
        assert_eq!(password_scalar(&ph), fixed::<32>(PW_SCALAR_HEX));
    }

    #[test]
    fn generated_messages_match_reference_vectors() {
        let a = generate(&password(), Role::Alice, &fixed::<64>(ALICE_RAND_HEX)).unwrap();
        let b = generate(&password(), Role::Bob, &fixed::<64>(BOB_RAND_HEX)).unwrap();
        assert_eq!(a.msg, fixed::<32>(ALICE_MSG_HEX));
        assert_eq!(b.msg, fixed::<32>(BOB_MSG_HEX));
    }

    #[test]
    fn alice_and_bob_derive_same_key() {
        let a = generate(&password(), Role::Alice, &fixed::<64>(ALICE_RAND_HEX)).unwrap();
        let b = generate(&password(), Role::Bob, &fixed::<64>(BOB_RAND_HEX)).unwrap();
        let ka = process(&a.ctx, &b.msg).unwrap();
        let kb = process(&b.ctx, &a.msg).unwrap();
        assert_eq!(ka, kb);
        assert_eq!(hex::encode(ka), KEY_HEX);
    }

    #[test]
    fn wrong_password_derives_different_key() {
        let a = generate(&password(), Role::Alice, &fixed::<64>(ALICE_RAND_HEX)).unwrap();
        let mut bad = password();
        bad[0] = b'6';
        bad[1] = b'5';
        bad[2] = b'4';
        bad[3] = b'3';
        bad[4] = b'2';
        bad[5] = b'1';
        let b = generate(&bad, Role::Bob, &fixed::<64>(BOB_RAND_HEX)).unwrap();
        let ka = process(&a.ctx, &b.msg).unwrap();
        let kb = process(&b.ctx, &a.msg).unwrap();
        assert_ne!(ka, kb);
        // 参考实现中 Bob 使用错误密码的 key（比对单方亦可确认 transcript 差异）
        assert_eq!(hex::encode(kb), WRONG_KEY_HEX);
    }

    #[test]
    fn corrupt_peer_message_is_rejected_or_produces_different_key() {
        let a = generate(&password(), Role::Alice, &fixed::<64>(ALICE_RAND_HEX)).unwrap();
        let b = generate(&password(), Role::Bob, &fixed::<64>(BOB_RAND_HEX)).unwrap();
        let mut corrupt = b.msg;
        corrupt[0] ^= 1;
        match process(&a.ctx, &corrupt) {
            Err(_) => {}
            Ok(k) => assert_ne!(k, process(&a.ctx, &b.msg).unwrap()),
        }
    }
}
